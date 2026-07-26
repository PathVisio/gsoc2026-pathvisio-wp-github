/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin;
import java.io.File;
import org.pathvisio.libgpml.model.PathwayModel;
public class PluginController 
{
     private String accessToken;
     private String authenticatedUsername;
     private boolean forkReady;
     private String confirmedBranch;
     private File activeGpmlFile;
     private PathwayModel activePathwayModel;
     
    public PluginController() 
    {
        this.accessToken = null;
        this.authenticatedUsername = null;
        this.forkReady = false;
        this.confirmedBranch = null;
        this.activeGpmlFile = null;
        this.activePathwayModel = null;
    }

    public String getAccessToken() 
    {
        return accessToken;
    }

    public void setAccessToken(String accessToken) 
    {
        this.accessToken = accessToken;
    }

    public String getAuthenticatedUsername() 
    {
        return authenticatedUsername;
    }

    public void setAuthenticatedUsername(String authenticatedUsername) 
    {
        this.authenticatedUsername = authenticatedUsername;
    }

    public boolean isForkReady() 
    {
        return forkReady;
    }

    public void setForkReady(boolean forkReady) 
    {
        this.forkReady = forkReady;
    }

    public String getConfirmedBranch() 
    {
        return confirmedBranch;
    }

    public void setConfirmedBranch(String confirmedBranch) 
    {
        this.confirmedBranch = confirmedBranch;
    }

    public File getActiveGpmlFile() 
    {
        return activeGpmlFile;
    }

    public void setActiveGpmlFile(File activeGpmlFile) 
    {
        this.activeGpmlFile = activeGpmlFile;
    }

    public PathwayModel getActivePathwayModel() 
    {
        return activePathwayModel;
    }

    public void setActivePathwayModel(PathwayModel activePathwayModel) 
    {
        this.activePathwayModel = activePathwayModel;
    }
    
}
