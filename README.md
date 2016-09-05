Jenkins Confluence Plugin
============================

This plugin allows a Jenkins job to modify an existing Confluence Page

Inspired by https://github.com/jenkinsci/confluence-publisher-plugin/

The above project was based around the soap apis which have become deprecated (see https://developer.atlassian.com/confdev/deprecated-apis/confluence-xml-rpc-and-soap-apis)

This current project is based on the REST api exposed by confluence (see https://docs.atlassian.com/atlassian-confluence/REST/latest-server/)

Current Feature Set:
* Integration with the credentials plugin
* Full page replacement
