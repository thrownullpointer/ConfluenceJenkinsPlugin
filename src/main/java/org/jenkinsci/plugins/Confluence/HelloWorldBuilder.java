package org.jenkinsci.plugins.Confluence;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
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
	public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) {
		List<UsernamePasswordCredentials> creds = CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, Jenkins.getInstance());
		UsernamePasswordCredentials c = creds.get(0);
		getDescriptor().getUseFrench();
		
		// SSLContext sslcontext = SSLContexts.custom().loadTrustMaterial(new File(System.getenv("javax.net.ssl.trustStore")), System.getenv("javax.net.ssl.trustStorePassword").toCharArray(), new TrustSelfSignedStrategy()).build();
		// Allow TLSv1 protocol only
		// SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslcontext, new String[] { "TLSv1" },null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

		HttpHost target = new HttpHost("localhost", 8090, "http");
		org.apache.http.client.CredentialsProvider credsProvider = new BasicCredentialsProvider();
		credsProvider.setCredentials(new AuthScope(target.getHostName(), target.getPort()),
				new org.apache.http.auth.UsernamePasswordCredentials(c.getUsername(), c.getPassword().getPlainText()));
		
		// Create AuthCache instance
		AuthCache authCache = new BasicAuthCache();
		// Generate BASIC scheme object and add it to the local auth cache
		BasicScheme basicAuth = new BasicScheme();
		authCache.put(target, basicAuth);

		// Add AuthCache to the execution context
		HttpClientContext context = HttpClientContext.create();
		context.setCredentialsProvider(credsProvider);
		context.setAuthCache(authCache);

		// CloseableHttpClient httpclient =
		// HttpClients.custom().setSSLSocketFactory(sslsf).setDefaultCredentialsProvider(credsProvider).build();
		CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();

		// Get current page version
		String pageObj = null;
		HttpEntity pageEntity = null;
		try {
			String body = FileUtils.readFileToString(new File(filePath));
			String uri = "http://localhost:8090" + "/rest/api/content/" + pageId;
			HttpGet getPageRequest = new HttpGet(uri);
			HttpResponse getPageResponse = httpclient.execute(target, getPageRequest, context);
			pageEntity = getPageResponse.getEntity();

			pageObj = IOUtils.toString(pageEntity.getContent());

			listener.getLogger().println("Get Page Request returned " + getPageResponse.getStatusLine().toString());
			listener.getLogger().println(pageObj);
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
			listener.getLogger().println("Put Page Request returned " + putPageResponse.getStatusLine().toString());
			listener.getLogger().println(IOUtils.toString(putPageEntity.getContent()));
			EntityUtils.consume(putPageEntity);
		} catch (Exception e) {
			listener.getLogger().print(e);
		}

	}

 
	  // Overridden for better type safety. // If your plugin doesn't really define any property on Descriptor, you don't have to do this.
	  

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		private boolean useFrench;

		public DescriptorImpl() {
			load();
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
		 *
		 * @param value
		 *            This parameter receives the value that the user has typed.
		 * @return Indicates the outcome of the validation. This is sent to the
		 *         browser.
		 *         <p>
		 *         Note that returning {@link FormValidation#error(String)} does
		 *         not prevent the form from being saved. It just means that a
		 *         message will be displayed to the user.
		 */
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
			// To persist global configuration information,
			// set that to properties and call save().
			useFrench = formData.getBoolean("useFrench");
			// ^Can also use req.bindJSON(this, formData);
			// (easier when there are many fields; need set* methods for this,
			// like setUseFrench)
			save();
			return super.configure(req, formData);
		}

		/**
		 * This method returns true if the global configuration says we should
		 * speak French.
		 *
		 * The method name is bit awkward because global.jelly calls this method
		 * to determine the initial state of the checkbox by the naming
		 * convention.
		 */
		public boolean getUseFrench() {
			return useFrench;
		}

	}
}
