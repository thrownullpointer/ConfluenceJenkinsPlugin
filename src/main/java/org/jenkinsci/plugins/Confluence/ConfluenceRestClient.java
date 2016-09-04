package org.jenkinsci.plugins.Confluence;

import javax.ws.rs.POST;
import javax.ws.rs.Path;

public interface ConfluenceRestClient {
	   @POST
	   @Path("/rest/api/content/")
	   public void createPage(String jsonPageBody);
}
