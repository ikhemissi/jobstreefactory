/*
 * The MIT License
 * 
 * Copyright (c) 2012, James Nord
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

import hudson.model.Action;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Action attached to the build that will record what we should do.
 * 
 * @author teilo
 */
public class BranchArgumentsAction implements Action {

	/** New branch maven version */
	private String releaseVersion = null;
	/** Current maven version */
	private String developmentVersion = null;
        /** New branch name */
        private String branchName;
        /** The scm base where the branch should be created */
        private String branchBase;
        
	/** release versions of sub modules (if different to the top level pom) */
	private Map<String, String> moduleReleaseVersions = null;

	/**
	 * list of the development versions of sub modules (if different to the top
	 * level pom)
	 */
	private Map<String, String> moduledevelopmentVersions = null;

	private transient String repoDescription;

	private transient String scmUsername;
	private transient String scmPassword;
	private transient String scmCommentPrefix;
	

	private transient boolean appendHusonUserName;
	private transient String hudsonUserName;

	public BranchArgumentsAction() {
		super();
	}

	public String getIconFileName() {
		// no icon.
		return null;
	}

	public String getDisplayName() {
		// don't display
		return null;
	}

	public String getUrlName() {
		// no url
		return null;
	}

	public String getReleaseVersion() {
		return releaseVersion;
	}

	public void setReleaseVersion(String releaseVersion) {
		this.releaseVersion = releaseVersion;
	}

	public String getDevelopmentVersion() {
		return developmentVersion;
	}

	public void setDevelopmentVersion(String developmentVersion) {
		this.developmentVersion = developmentVersion;
	}
        
        public String getBranchName() {
		return branchName;
	}

	public void setBranchName(String branchName) {
		this.branchName = branchName;
	}
        
        public String getBranchBase() {
		return branchBase;
	}

	public void setBranchBase(String branchBase) {
		this.branchBase = branchBase;
	}

	public Map<String, String> getModuleReleaseVersions() {
		return Collections.unmodifiableMap(moduleReleaseVersions);
	}

	public void setModuleReleaseVersions(Map<String, String> moduleReleaseVersions) {
		this.moduleReleaseVersions = new TreeMap<String, String>(moduleReleaseVersions);
	}

	public Map<String, String> getModuledevelopmentVersions() {
		return Collections.unmodifiableMap(moduledevelopmentVersions);
	}

	public void setModuledevelopmentVersions(Map<String, String> moduledevelopmentVersions) {
		this.moduledevelopmentVersions = new TreeMap<String, String>(moduledevelopmentVersions);
	}

	public String getRepoDescription() {
		return repoDescription;
	}

	public void setRepoDescription(String repoDescription) {
		this.repoDescription = repoDescription;
	}

	public String getScmUsername() {
		return scmUsername;
	}

	public void setScmUsername(String scmUsername) {
		this.scmUsername = scmUsername;
	}

	public String getScmPassword() {
		return scmPassword;
	}

	public void setScmPassword(String scmPassword) {
		this.scmPassword = scmPassword;
	}

	public String getScmCommentPrefix() {
		return scmCommentPrefix;
	}

	public void setScmCommentPrefix(String scmCommentPrefix) {
		this.scmCommentPrefix = scmCommentPrefix;
	}

	public boolean isAppendHusonUserName() {
		return appendHusonUserName;
	}

	public void setAppendHusonUserName(boolean appendHusonUserName) {
		this.appendHusonUserName = appendHusonUserName;
	}

	public String getHudsonUserName() {
		return hudsonUserName;
	}

	public void setHudsonUserName(String hudsonUserName) {
		this.hudsonUserName = hudsonUserName;
	}

}
