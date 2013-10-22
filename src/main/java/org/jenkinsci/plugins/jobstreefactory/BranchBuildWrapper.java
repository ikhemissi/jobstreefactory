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
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Run;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.RunList;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.Localizable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
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
	
	/** For backwards compatibility with older configurations. @deprecated */
	@Deprecated
	public transient boolean              defaultVersioningMode;
	
	private String                        scmUserEnvVar                = "";
	private String                        scmPasswordEnvVar            = "";
	private String                        releaseEnvVar                = DescriptorImpl.DEFAULT_RELEASE_ENVVAR;
	private String                        releaseGoals                 = DescriptorImpl.DEFAULT_RELEASE_GOALS;
	private String                        dryRunGoals                  = DescriptorImpl.DEFAULT_DRYRUN_GOALS;
	public boolean                        selectCustomScmCommentPrefix = DescriptorImpl.DEFAULT_SELECT_CUSTOM_SCM_COMMENT_PREFIX;
	public boolean                        selectAppendHudsonUsername   = DescriptorImpl.DEFAULT_SELECT_APPEND_HUDSON_USERNAME;
	public boolean                        selectScmCredentials         = DescriptorImpl.DEFAULT_SELECT_SCM_CREDENTIALS;
	
	public int                            numberOfReleaseBuildsToKeep  = DescriptorImpl.DEFAULT_NUMBER_OF_RELEASE_BUILDS_TO_KEEP;
	
	@DataBoundConstructor
	public BranchBuildWrapper(String releaseGoals, String dryRunGoals, boolean selectCustomScmCommentPrefix, boolean selectAppendHudsonUsername, boolean selectScmCredentials, String releaseEnvVar, String scmUserEnvVar, String scmPasswordEnvVar, int numberOfReleaseBuildsToKeep) {
		super();
		this.releaseGoals = releaseGoals;
		this.dryRunGoals = dryRunGoals;
		this.selectCustomScmCommentPrefix = selectCustomScmCommentPrefix;
		this.selectAppendHudsonUsername = selectAppendHudsonUsername;
		this.selectScmCredentials = selectScmCredentials;
		this.releaseEnvVar = releaseEnvVar;
		this.scmUserEnvVar = scmUserEnvVar;
		this.scmPasswordEnvVar = scmPasswordEnvVar;
		this.numberOfReleaseBuildsToKeep = numberOfReleaseBuildsToKeep;
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

		buildGoals.append("-DdevelopmentVersion=").append(args.getDevelopmentVersion()).append(' ');
		buildGoals.append("-DreleaseVersion=").append(args.getReleaseVersion()).append(' ');

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

		if (args.getScmTagName() != null) {
			buildGoals.append("-Dtag=").append(args.getScmTagName()).append(' ');
		}

		if (args.isDryRun()) {
			buildGoals.append(getDryRunGoals());
		}
		else {
			buildGoals.append(getReleaseGoals());
		}

		build.addAction(new BranchArgumentInterceptorAction(buildGoals.toString()));
		build.addAction(new BranchBadgeAction(args.getReleaseVersion(), args.isDryRun()));

		return new Environment() {

			@Override
			public void buildEnvVars(Map<String, String> env) {
				if (StringUtils.isNotBlank(releaseEnvVar)) {
					// inform others that we are doing a release build
					env.put(releaseEnvVar, "true");
				}
			}

			@Override
			public boolean tearDown(@SuppressWarnings("rawtypes") AbstractBuild bld, BuildListener lstnr)
					throws IOException, InterruptedException {
				boolean retVal = true;
				final MavenModuleSet mmSet = getModuleSet(bld);
				BranchArgumentsAction args = bld.getAction(BranchArgumentsAction.class);
				
				if (args.isDryRun()) {
					lstnr.getLogger().println("[M2Release] its only a dryRun, no need to mark it for keep");
				}

				int buildsKept = 0;
				if (bld.getResult() != null && bld.getResult().isBetterOrEqualTo(Result.SUCCESS) && !args.isDryRun()) {
					if (numberOfReleaseBuildsToKeep > 0 || numberOfReleaseBuildsToKeep == -1) {
						// keep this build.
						lstnr.getLogger().println("[M2Release] assigning keep build to current build.");
						bld.keepLog();
						buildsKept++;
					}

					// the value may have changed since a previous release so go searching...
					log.debug("looking for extra release builds to lock/unlock.");
					for (Run run : (RunList<? extends Run>) (bld.getProject().getBuilds())) {
						log.debug("checking build #{}", run.getNumber());
						if (isSuccessfulReleaseBuild(run)) {
							log.debug("build #{} was successful.", run.getNumber());
							if (bld.getNumber() != run.getNumber()) { // not sure we still need this check..
								if (shouldKeepBuildNumber(numberOfReleaseBuildsToKeep, buildsKept)) {
									buildsKept++;
									if (!run.isKeepLog()) {
										lstnr.getLogger().println(
												"[M2Release] assigning keep build to build " + run.getNumber());
										run.keepLog(true);
									}
								}
								else {
									if (run.isKeepLog()) {
										lstnr.getLogger().println(
												"[M2Release] removing keep build from build " + run.getNumber());
										run.keepLog(false);
									}
								}
							}
						}
						else {
							log.debug("build #{} was NOT successful release build.", run.getNumber());
						}
					}
				}

				return retVal;
			}

			/**
			 * evaluate if the specified build is a sucessful release build (not including dry runs)
			 * @param run the run to check
			 * @return <code>true</code> if this is a successful release build that is not a dry run.
			 */
			private boolean isSuccessfulReleaseBuild(Run run) {
				BranchBadgeAction a = run.getAction(BranchBadgeAction.class);
				if (a != null && !run.isBuilding() && run.getResult().isBetterOrEqualTo(Result.SUCCESS) && !a.isDryRun()) {
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
	
	public int getNumberOfReleaseBuildsToKeep() {
		return numberOfReleaseBuildsToKeep;
	}
	
	public void setNumberOfReleaseBuildsToKeep(int numberOfReleaseBuildsToKeep) {
		this.numberOfReleaseBuildsToKeep = numberOfReleaseBuildsToKeep;
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


	public static boolean hasReleasePermission(@SuppressWarnings("rawtypes") AbstractProject job) {
		return job.hasPermission(DescriptorImpl.CREATE_BRANCH);
	}


	public static void checkReleasePermission(@SuppressWarnings("rawtypes") AbstractProject job) {
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
		return StringUtils.isBlank(releaseGoals) ? DescriptorImpl.DEFAULT_RELEASE_GOALS : releaseGoals;
	}
	
	public String getDryRunGoals() {
		return StringUtils.isBlank(dryRunGoals) ? DescriptorImpl.DEFAULT_DRYRUN_GOALS : dryRunGoals;
	}
	

	/**
	 * Evaluate if the current build should be a release build.
	 * @return <code>true</code> if this build is a release build.
	 */
	private boolean isReleaseBuild(@SuppressWarnings("rawtypes") AbstractBuild build) {
		return (build.getCause(BranchReason.class) != null);
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
			Permission tmpPerm = null;
			try {
				// Jenkins changed the security model in a non backward compatible way :-(
				// JENKINS-10661
				Class<?> permissionScopeClass = Class.forName("hudson.security.PermissionScope");
				Object psArr = Array.newInstance(permissionScopeClass, 2);
				Field f;
				f = permissionScopeClass.getDeclaredField("JENKINS");
				Array.set(psArr, 0, f.get(null));
				f = permissionScopeClass.getDeclaredField("ITEM");
				Array.set(psArr, 1, f.get(null));
				
				Constructor<Permission> ctor = Permission.class.getConstructor(PermissionGroup.class, 
						String.class, 
						Localizable.class, 
						Permission.class, 
//						boolean.class,
						permissionScopeClass);
						//permissionScopes.getClass());
				tmpPerm = ctor.newInstance(Item.PERMISSIONS, 
				                           "Release",
				                            Messages._CreateBranchPermission_Description(),
				                            Hudson.ADMINISTER,
//				                            true,
				                            f.get(null));
				LoggerFactory.getLogger(BranchBuildWrapper.class).info("Using new style Permission with PermissionScope");

			}
			// all these exceptions are Jenkins < 1.421 or Hudson
			// wouldn't multicatch be nice!
			catch (NoSuchMethodException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (InvocationTargetException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (IllegalArgumentException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (IllegalAccessException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (InstantiationException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (NoSuchFieldException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			catch (ClassNotFoundException ex) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new PermissionScope not detected. {}", ex.getMessage());
			}
			if (tmpPerm == null) {
				LoggerFactory.getLogger(BranchBuildWrapper.class).warn("Using Legacy Permission as new style permission with PermissionScope failed");
				tmpPerm = new Permission(Item.PERMISSIONS,
				                         "Release", //$NON-NLS-1$
				                          Messages._CreateBranchPermission_Description(),
				                          Hudson.ADMINISTER);
			}
			CREATE_BRANCH = tmpPerm;
		}

		public static final String     DEFAULT_RELEASE_GOALS = "-Dresume=false release:prepare release:perform"; //$NON-NLS-1$
		public static final String     DEFAULT_DRYRUN_GOALS = "-Dresume=false -DdryRun=true release:prepare"; //$NON-NLS-1$
		public static final String     DEFAULT_RELEASE_ENVVAR = "IS_M2RELEASEBUILD"; //$NON-NLS-1$
		public static final String     DEFAULT_RELEASE_VERSION_ENVVAR = "MVN_RELEASE_VERSION"; //$NON-NLS-1$
		public static final String     DEFAULT_DEV_VERSION_ENVVAR = "MVN_DEV_VERSION"; //$NON-NLS-1$
		public static final String     DEFAULT_DRYRUN_ENVVAR = "MVN_ISDRYRUN"; //$NON-NLS-1$

		public static final boolean    DEFAULT_SELECT_CUSTOM_SCM_COMMENT_PREFIX = false;
		public static final boolean    DEFAULT_SELECT_APPEND_HUDSON_USERNAME    = false;
		public static final boolean    DEFAULT_SELECT_SCM_CREDENTIALS           = false;

		public static final int        DEFAULT_NUMBER_OF_RELEASE_BUILDS_TO_KEEP = 1;
		


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
			save();
			return true; // indicate that everything is good so far
		}

		@Override
		public String getDisplayName() {
			return Messages.Wrapper_DisplayName();
		}
	}
}
