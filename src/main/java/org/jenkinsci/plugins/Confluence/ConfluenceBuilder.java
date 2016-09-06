package org.jenkinsci.plugins.Confluence;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.*;
import jenkins.model.*;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

public class ConfluenceBuilder extends Builder implements SimpleBuildStep {

    private final String name;
    private final String pageId;
    private final String filePath;
    private final String credentialsId;
    private final Boolean isRelativePath;
    private String hostName;

    @DataBoundConstructor
    public ConfluenceBuilder(String name, String pageId, String filePath, String credentialsId,Boolean isRelativePath) {
        this.name = name;
        this.pageId = pageId;
        this.filePath = filePath;
        this.credentialsId = credentialsId;
        this.isRelativePath = isRelativePath;
    }

    public String getName() {
        return name;
    }

    public String getPageId() {
        return pageId;
    }

    public String getFilePath() {
        return filePath;
    }
    
    public String getCredentialsId() {
        return credentialsId;
    }
    
    public Boolean getIsRelativePath() {
        return isRelativePath;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException {
        try {
            this.hostName = getDescriptor().getHostName();
            URL url = new URL(hostName);

            Integer port = url.getPort();
            if (port < 0) {
                port = 80;
            }

            UsernamePasswordCredentials credential = findCredentials(this.credentialsId, url.getHost(), port);

            HttpHost target = new HttpHost(url.getHost(), port, url.getProtocol());
            org.apache.http.client.CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(target.getHostName(), target.getPort()),
                    new org.apache.http.auth.UsernamePasswordCredentials(credential.getUsername(), credential.getPassword().getPlainText()));

            // Create AuthCache instance
            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(target, basicAuth);

            // Add AuthCache to the execution context
            HttpClientContext context = HttpClientContext.create();
            context.setCredentialsProvider(credsProvider);
            context.setAuthCache(authCache);

            HttpClientBuilder builder = HttpClientBuilder.create();
            RegistryBuilder<ConnectionSocketFactory> schemeRegistryBuilder = RegistryBuilder.<ConnectionSocketFactory>create();
            schemeRegistryBuilder.register("http", PlainConnectionSocketFactory.getSocketFactory());

            SSLContextBuilder b = new SSLContextBuilder();
            b.loadTrustMaterial(null, new TrustSelfSignedStrategy());
            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(b.build());
            builder.setSSLSocketFactory(sslsf);
            schemeRegistryBuilder.register("https", sslsf);

            BasicHttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(schemeRegistryBuilder.build());
            builder.setConnectionManager(connectionManager);
            builder.setDefaultCredentialsProvider(credsProvider);
            CloseableHttpClient httpclient = builder.build();

            // Get current page version
            String pageObj = null;
            HttpEntity pageEntity = null;

            String body = null;
            if(isRelativePath){
                String fullPath = workspace.getRemote() + File.separator +  filePath;
                body = FileUtils.readFileToString(new File(fullPath));
            }else{
                body = FileUtils.readFileToString(new File(filePath));
            }
            
            String uri = this.hostName + "/rest/api/content/" + pageId + "?expand=body.storage,version,ancestors";
            HttpGet getPageRequest = new HttpGet(uri);
            HttpResponse getPageResponse = httpclient.execute(target, getPageRequest, context);
            pageEntity = getPageResponse.getEntity();

            pageObj = IOUtils.toString(pageEntity.getContent());

            listener.getLogger().println("Get Page Request returned " + getPageResponse.getStatusLine());
            // listener.getLogger().println(pageObj);
            if (pageEntity != null) {
                EntityUtils.consume(pageEntity);
            }

            org.json.JSONObject page = new org.json.JSONObject(pageObj);
            page.getJSONObject("body").getJSONObject("storage").put("value", body);
            int currentVersion = page.getJSONObject("version").getInt("number");
            page.getJSONObject("version").put("number", currentVersion + 1);

            // Send update request
            HttpEntity putPageEntity = null;
            HttpPut putPageRequest = new HttpPut(uri);
            StringEntity entity = new StringEntity(page.toString(), ContentType.APPLICATION_JSON);
            putPageRequest.setEntity(entity);
            HttpResponse putPageResponse = httpclient.execute(putPageRequest);
            putPageEntity = putPageResponse.getEntity();
            listener.getLogger().println("Put Request returned " + putPageResponse.getStatusLine());
            // listener.getLogger().println(IOUtils.toString(putPageEntity.getContent()));
            EntityUtils.consume(putPageEntity);
            httpclient.close();

        } catch (Exception e) {
            listener.getLogger().println(e);
        }
    }

    public UsernamePasswordCredentials findCredentials(String credentialsId, String host, int port) {
        return CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, Jenkins.getActiveInstance(), ACL.SYSTEM,
                new HostnamePortRequirement(host, port)), CredentialsMatchers.withId(credentialsId));
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private String hostName;

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckPageId(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateNonNegativeInteger(value);
        }
        
        public FormValidation doCheckHostName(@QueryParameter String value) throws IOException, ServletException {
            if(value == null || value.length() < 1){
                return FormValidation.error("Confluence plugin won't work with an empty hostname");
            }
            else if(!value.contains(":")){
                return FormValidation.warning("If a port number isn't included port 80 is assumed");
            } else if(value.endsWith("/")){
                return FormValidation.warning("A trailing slash may cause issues, do not include it");
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Confluence Hook";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            hostName = formData.getString("hostName");
            save();
            return super.configure(req, formData);
        }

        public String getHostName() {
            return hostName;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            List<StandardUsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, context,
                    ACL.SYSTEM, Collections.<DomainRequirement>emptyList());
            return new StandardListBoxModel().withEmptySelection().withMatching(CredentialsMatchers.always(), credentials);
        }

    }
}
