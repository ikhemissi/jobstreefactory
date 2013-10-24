/*
 * The MIT License
 * 
 * Copyright (c) 2010, NDS Group Ltd., James Nord
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.jobstreefactory;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.AbstractMavenProject;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Builder;

import java.io.IOException;
import java.util.Map;
import jenkins.model.Jenkins;


import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a {@link MavenBuild} to be able to run the <a
 * href="http://maven.apache.org/plugins/maven-release-plugin/">maven release plugin</a> on demand
 * 
 * @author James Nord
 * @author Dominik Bartholdi
 * @version 0.3
 * @since 0.1
 */
public class BranchBuildWrapper extends BuildWrapper {
	
	
	private transient Logger log = LoggerFactory.getLogger(BranchBuildWrapper.class);
	
	private String                        scmUserEnvVar                = "";
	private String                        scmPasswordEnvVar            = "";
        private String                        scmBranchBaseDefault         = "";
	private String                        releaseEnvVar                = DescriptorImpl.DEFAULT_RELEASE_ENVVAR;
	private String                        releaseGoals                 = DescriptorImpl.DEFAULT_RELEASE_BRANCH_GOALS;
	public boolean                        selectCustomScmCommentPrefix = DescriptorImpl.DEFAULT_SELECT_CUSTOM_SCM_COMMENT_PREFIX;
	public boolean                        selectAppendHudsonUsername   = DescriptorImpl.DEFAULT_SELECT_APPEND_HUDSON_USERNAME;
	public boolean                        selectScmCredentials         = DescriptorImpl.DEFAULT_SELECT_SCM_CREDENTIALS;
	
	@DataBoundConstructor
	public BranchBuildWrapper(String releaseGoals, boolean selectCustomScmCommentPrefix, boolean selectAppendHudsonUsername, boolean selectScmCredentials, String releaseEnvVar, String scmUserEnvVar, String scmPasswordEnvVar, int numberOfBranchesToKeep) {
		super();
		this.releaseGoals = releaseGoals;
		this.selectCustomScmCommentPrefix = selectCustomScmCommentPrefix;
		this.selectAppendHudsonUsername = selectAppendHudsonUsername;
		this.selectScmCredentials = selectScmCredentials;
		this.releaseEnvVar = releaseEnvVar;
		this.scmUserEnvVar = scmUserEnvVar;
		this.scmPasswordEnvVar = scmPasswordEnvVar;
	}


	@Override
	public Environment setUp(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, final BuildListener listener)
	                                                                                              throws IOException,
	                                                                                              InterruptedException {

		if (!isReleaseBuild(build)) {
			// we are not performing a release so don't need a custom tearDown.
			return new Environment() {
				/** intentionally blank */
				@Override
				public void buildEnvVars(Map<String, String> env) {
					if (StringUtils.isNotBlank(releaseEnvVar)) {
						// inform others that we are NOT doing a release build
						env.put(releaseEnvVar, "false");
					}
				}
			};
		}
		
		// we are a release build
		BranchArgumentsAction args = build.getAction(BranchArgumentsAction.class);
		StringBuilder buildGoals = new StringBuilder();

		buildGoals.append("-DdevelopmentVersion=").append(args.getDevelopmentVersion()).append(' '); // current maven version
		buildGoals.append("-DreleaseVersion=").append(args.getReleaseVersion()).append(' '); // new branch maven version
                buildGoals.append("-DbranchName=").append(args.getBranchName()).append(' '); // new branch name

		if (args.getScmUsername() != null) {
			buildGoals.append("-Dusername=").append(args.getScmUsername()).append(' ');
		}

		if (args.getScmPassword() != null) {
			buildGoals.append("-Dpassword=").append(args.getScmPassword()).append(' ');
		}

		if (args.getScmCommentPrefix() != null) {
			buildGoals.append("\"-DscmCommentPrefix=");
			buildGoals.append(args.getScmCommentPrefix());
			if (args.isAppendHusonUserName()) {
				buildGoals.append(String.format("(%s)", args.getHudsonUserName()));
			}
			buildGoals.append("\" ");
		}

		buildGoals.append(getReleaseGoals());

		build.addAction(new BranchArgumentInterceptorAction(buildGoals.toString()));
		build.addAction(new BranchBadgeAction(args.getReleaseVersion()));

		return new Environment() {

			@Override
			public void buildEnvVars(Map<String, String> env) {
				if (StringUtils.isNotBlank(releaseEnvVar)) {
					// inform others that we are doing a release build
					env.put(releaseEnvVar, "true");
				}
			}

			@Override
			public boolean tearDown(@SuppressWarnings("rawtypes") AbstractBuild bld, BuildListener lstnr) throws IOException, InterruptedException {
				return true;
			}

			/**
			 * evaluate if the specified build is a sucessful release build (not including dry runs)
			 * @param run the run to check
			 * @return <code>true</code> if this is a successful release build that is not a dry run.
			 */
			private boolean isSuccessfulReleaseBuild(Run run) {
				BranchBadgeAction a = run.getAction(BranchBadgeAction.class);
				if (a != null && !run.isBuilding() && run.getResult().isBetterOrEqualTo(Result.SUCCESS)) {
					return true;
				}
				return false;
			}
			
			private boolean shouldKeepBuildNumber(int numToKeep, int numKept) {
				if (numToKeep == -1) {
					return true;
				}
				return numKept < numToKeep;
			}
		};
	}

	
	public boolean isSelectCustomScmCommentPrefix() {
		return selectCustomScmCommentPrefix;
	}

	public void setSelectCustomScmCommentPrefix(boolean selectCustomScmCommentPrefix) {
		this.selectCustomScmCommentPrefix = selectCustomScmCommentPrefix;
	}

	public boolean isSelectAppendHudsonUsername() {
		return selectAppendHudsonUsername;
	}

	public void setSelectAppendHudsonUsername(boolean selectAppendHudsonUsername) {
		this.selectAppendHudsonUsername = selectAppendHudsonUsername;
	}

	private MavenModuleSet getModuleSet(AbstractBuild<?,?> build) {
		if (build instanceof MavenBuild) {
			MavenBuild m2Build = (MavenBuild) build;
			MavenModule mm = m2Build.getProject();
			MavenModuleSet mmSet = mm.getParent();
			return mmSet;
		}
		else if (build instanceof MavenModuleSetBuild) {
			MavenModuleSetBuild m2moduleSetBuild = (MavenModuleSetBuild) build;
			MavenModuleSet mmSet = m2moduleSetBuild.getProject();
			return mmSet;
		}
		else {
			return null;
		}
	}


	public static boolean hasBranchPermission(@SuppressWarnings("rawtypes") AbstractProject job) {
		return job.hasPermission(DescriptorImpl.CREATE_BRANCH);
	}


	public static void checkBranchPermission(@SuppressWarnings("rawtypes") AbstractProject job) {
		job.checkPermission(DescriptorImpl.CREATE_BRANCH);
	}

	public String getReleaseEnvVar() {
		return releaseEnvVar;
	}

	public String getScmUserEnvVar() {
		return scmUserEnvVar;
	}
	
	public String getScmPasswordEnvVar() {
		return scmPasswordEnvVar;
	}
	
	public String getReleaseGoals() {
		return StringUtils.isBlank(releaseGoals) ? DescriptorImpl.DEFAULT_RELEASE_BRANCH_GOALS : releaseGoals;
	}
	

	/**
	 * Evaluate if the current build should be a release build.
	 * @return <code>true</code> if this build is a release build.
	 */
	private boolean isReleaseBuild(@SuppressWarnings("rawtypes") AbstractBuild build) {
		return (build.getCause(BranchReason.class) != null);
	}

        
        public String getScmBranchBaseDefault() {
            return getDescriptor().getScmBranchBaseDefault();
        }

	/** Recreate the logger on de-serialisation. */
	private Object readResolve() {
		log = LoggerFactory.getLogger(BranchBuildWrapper.class);
		return this;
	}

	@Override
	public Action getProjectAction(@SuppressWarnings("rawtypes") AbstractProject job) {
		return new BranchAction((MavenModuleSet) job, selectCustomScmCommentPrefix, selectAppendHudsonUsername, selectScmCredentials);
	}

	/**
	 * Hudson defines a method {@link Builder#getDescriptor()}, which returns the corresponding
	 * {@link Descriptor} object. Since we know that it's actually {@link DescriptorImpl}, override the method
	 * and give a better return type, so that we can access {@link DescriptorImpl} methods more easily. This is
	 * not necessary, but just a coding style preference.
	 */
	@Override
	public DescriptorImpl getDescriptor() {
		// see Descriptor javadoc for more about what a descriptor is.
		return (DescriptorImpl) super.getDescriptor();
	}

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {
		
		public static final Permission CREATE_BRANCH;

		static {
                    CREATE_BRANCH = new Permission(Jenkins.PERMISSIONS, "Branch", Messages._CreateBranchPermission_Description(), Jenkins.ADMINISTER, PermissionScope.JENKINS);
		}

		public static final String     DEFAULT_RELEASE_BRANCH_GOALS = "--batch-mode -Dresume=false -DupdateDependencies=true -DupdateWorkingCopyVersions=false -DupdateBranchVersions=true release:branch"; //$NON-NLS-1$
		public static final String     DEFAULT_RELEASE_ENVVAR = "IS_JOBSTREEFACTORYBUILD"; //$NON-NLS-1$
		public static final String     DEFAULT_RELEASE_VERSION_ENVVAR = "MVN_RELEASE_VERSION"; //$NON-NLS-1$
		public static final String     DEFAULT_DEV_VERSION_ENVVAR = "MVN_DEV_VERSION"; //$NON-NLS-1$

		public static final boolean    DEFAULT_SELECT_CUSTOM_SCM_COMMENT_PREFIX = false;
		public static final boolean    DEFAULT_SELECT_APPEND_HUDSON_USERNAME    = false;
		public static final boolean    DEFAULT_SELECT_SCM_CREDENTIALS           = false;	

                private String  scmBranchBaseDefault = "";

		public DescriptorImpl() {
			super(BranchBuildWrapper.class);
			load();
		}


		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return (item instanceof AbstractMavenProject);
		}

		@Override
                public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException {
                        JSONObject globalConfParams = json.getJSONObject("globaljobstreefactory"); //$NON-NLS-1$
                        scmBranchBaseDefault = Util.fixEmptyAndTrim(globalConfParams.getString("scmBranchBaseDefault")); //$NON-NLS-1$
                        save();
                        return true; // indicate that everything is good so far
                }

		@Override
		public String getDisplayName() {
			return Messages.Wrapper_DisplayName();
		}
                
                public String getScmBranchBaseDefault() {
			return scmBranchBaseDefault;
		}
                
                public void setScmBranchBaseDefault(String scmBranchBaseDefault) {
                        this.scmBranchBaseDefault = scmBranchBaseDefault;
                }
                
	}
}
