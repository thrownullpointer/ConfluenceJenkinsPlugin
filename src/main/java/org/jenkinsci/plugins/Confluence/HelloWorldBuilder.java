package org.jenkinsci.plugins.Confluence;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
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
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.model.*;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link HelloWorldBuilder} is created. The created instance is persisted to
 * the project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked.
 *
 * @author Kohsuke Kawaguchi
 */
public class HelloWorldBuilder extends Builder implements SimpleBuildStep {

    private final String name;
    private final String pageId;
    private final String filePath;
    private String hostName;

    @DataBoundConstructor
    public HelloWorldBuilder(String name, String pageId, String filePath) {
        this.name = name;
        this.pageId = pageId;
        this.filePath = filePath;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getName() {
        return name;
    }

    public String getPageId() {
        return pageId;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException {
        try {
            this.hostName = getDescriptor().getHostName();
            listener.getLogger().println(hostName);
            URL url = new URL(hostName);

            Integer port = url.getPort();
            if (port < 0) {
                port = 80;
            }

            listener.getLogger().println(url.getHost());
            listener.getLogger().println(port);
            listener.getLogger().println(url.getProtocol());
            listener.getLogger().println("Lookup time!");

            List<UsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class,
                    Jenkins.getActiveInstance(), ACL.SYSTEM, new HostnamePortRequirement(url.getHost(), port));
            if (!credentials.isEmpty()) {
                UsernamePasswordCredentials credential = credentials.get(0);

                HttpHost target = new HttpHost(url.getHost(), port, url.getProtocol());
                org.apache.http.client.CredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(new AuthScope(target.getHostName(), target.getPort()), new org.apache.http.auth.UsernamePasswordCredentials(credential.getUsername(), credential.getPassword().getPlainText()));

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

                String body = FileUtils.readFileToString(new File(filePath));
                String uri = this.hostName + "/rest/api/content/" + pageId + "?expand=body.storage,version,ancestors";
                HttpGet getPageRequest = new HttpGet(uri);
                HttpResponse getPageResponse = httpclient.execute(target, getPageRequest, context);
                pageEntity = getPageResponse.getEntity();

                pageObj = IOUtils.toString(pageEntity.getContent());

                // listener.getLogger().println("Get Page Request returned " +
                // getPageResponse.getStatusLine().toString());
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
                // listener.getLogger().println("Put Page Request returned " +
                // putPageResponse.getStatusLine().toString());
                // listener.getLogger().println(IOUtils.toString(putPageEntity.getContent()));
                EntityUtils.consume(putPageEntity);
                httpclient.close();
            } else {
                throw new AbortException("Could not find credentials, confirm they exist for this domain");
            }
        } catch (Exception e) {
            listener.getLogger().println(e);
        }
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

        public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public FormValidation doCheckPageId(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Page id should not be left null");
            else if (!Pattern.matches("[0-9]+", value))
                return FormValidation.error("Page id should be a numeric value");
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

    }
}
