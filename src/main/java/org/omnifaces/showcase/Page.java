/*
 * Copyright 2012 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.showcase;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.omnifaces.showcase.App.scrape;
import static org.omnifaces.util.Faces.evaluateExpressionGet;
import static org.omnifaces.util.Faces.getMetadataAttributes;
import static org.omnifaces.util.Faces.getResourceAsStream;
import static org.omnifaces.util.Utils.toByteArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.faces.FacesException;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.omnifaces.model.tree.ListTreeModel;

/**
 * This class represents a page. All pages are available as a tree model by {@link App#getMenu()}. The current page is
 * produced as model (request scoped named bean) by {@link App#getPage()} based on the current view ID.
 *
 * @author Bauke Scholtz
 */
public class Page extends ListTreeModel<Page> {

	// Constants ------------------------------------------------------------------------------------------------------

	private static final long serialVersionUID = 1L;
	private static final String API_PATH = "org/omnifaces/";
	private static final String ERROR_LOADING_PAGE_DESCRIPTION = "Unable to load description of %s";
	private static final String ERROR_LOADING_PAGE_SOURCE = "Unable to load source code of %s";

	// Properties -----------------------------------------------------------------------------------------------------

	private String title;
	private String path;
	private String viewId;
	private String description;
	private List<Source> sources;
	private Documentation documentation;
	private AtomicBoolean loaded = new AtomicBoolean();

	// Constructors ---------------------------------------------------------------------------------------------------

	public Page() {
		// Keep default c'tor alive for CDI.
	}

	Page(String title) {
		this.title = title;
	}

	Page(String path, String viewId, String title) {
		this.path = path;
		this.viewId = viewId;
		this.title = title;
	}

	// Initialization -------------------------------------------------------------------------------------------------

	Page loadIfNecessary() {
		if (!(loaded.getAndSet(true) || path == null)) {
			try {
				Map<String, Object> attributes = getMetadataAttributes();
				List<String> apiPaths = new ArrayList<>(getCommaSeparatedAttribute(attributes, "api.path"));
				List<String> vdlPaths = getCommaSeparatedAttribute(attributes, "vdl.paths");
				List<String> srcPaths = getCommaSeparatedAttribute(attributes, "src.paths");
				List<String> jsPaths = getCommaSeparatedAttribute(attributes, "js.paths");
				description = loadDescription(apiPaths);
				sources = loadSources(path, srcPaths);
				documentation = (apiPaths.size() + vdlPaths.size() + jsPaths.size() > 0) ? new Documentation(apiPaths, vdlPaths, jsPaths) : null;
			}
			catch (Exception e) {
				loaded.set(false);
				throw e;
			}
		}

		return this;
	}

	private static List<String> getCommaSeparatedAttribute(Map<String, Object> attributes, String key) {
		String attribute = (String) attributes.get(key);
		return (attribute == null) ? emptyList() : asList(attribute.trim().split("\\s*,\\s*"));
	}

	private static String loadDescription(List<String> apiPaths) {
		if (apiPaths.size() == 1) {
			apiPaths.set(0, API_PATH + apiPaths.get(0));
			String url = String.format("%s%s.html", evaluateExpressionGet("#{_apiURL}"), apiPaths.get(0));

			try {
				// TODO: build javadoc.jar into webapp somehow and scrape from it instead.
				Elements description = scrape(url, ".description>ul>li");
				Elements descriptionBlock = description.select(".block");

				for (Element link : description.select("a")) { // Turn relative links into absolute links.
					link.attr("href", link.absUrl("href"));
				}

				for (Element pre : descriptionBlock.select("pre")) { // Enable prettify on code blocks.
					String content = pre.addClass("prettyprint").html().trim().replace("\n ", "\n");
					String type = content.startsWith("&lt;") ? "xhtml" : "java";
					pre.html("<code class='lang-" + type + "'>" + content + "</code>");
				}

				Elements seeAlso = description.select("dt:has(.seeLabel)+dd a:has(code)");

				for (Element link : seeAlso) {
					String href = link.absUrl("href");
					apiPaths.add(href.substring(href.indexOf(API_PATH), href.lastIndexOf('.')));
				}

				return descriptionBlock.outerHtml();
			}
			catch (IOException e) {
				throw new FacesException(String.format(ERROR_LOADING_PAGE_DESCRIPTION, url), e);
			}
		}

		return null;
	}

	private static List<Source> loadSources(String pagePath, List<String> srcPaths) {
		List<Source> sources = new ArrayList<>(1 + srcPaths.size());
		String sourceCode = loadSourceCode(pagePath);
		String[] meta = sourceCode.split("<ui:define name=\"demo-meta\">");
		String[] demo = sourceCode.split("<ui:define name=\"demo\">");
		StringBuilder demoSourceCode = new StringBuilder();

		if (meta.length > 1) {
			// Yes, ugly, but it's faster than a XML parser and it's internal code anyway.
			demoSourceCode.append(meta[1].split("</ui:define>")[0].trim()).append("\n\n");
		}

		if (demo.length > 1) {
			demoSourceCode.append(demo[1].split("</ui:define>")[0].trim());
		}

		if (demoSourceCode.length() > 0) {
			// The 8 leading spaces are trimmed so that the whole demo code block is indented back.
			String code = demoSourceCode.toString().replace("\n        ", "\n").trim();
			sources.add(new Source("Demo", "xhtml", code));
		}

		for (String srcPath : srcPaths) {
			String title = srcPath;

			if (title.contains("/")) {
				title = title.substring(title.lastIndexOf('/') + 1, title.length());
			}

			if (title.endsWith(".java")) {
				title = title.substring(0, title.indexOf('.'));
			}

			String type = srcPath.substring(srcPath.lastIndexOf('.') + 1);
			String code = loadSourceCode((srcPath.startsWith("/") ? "" : "/WEB-INF/") + srcPath);
			sources.add(new Source(title, type, code));
		}

		return sources;
	}

	private static String loadSourceCode(String path) {
		InputStream input = getResourceAsStream(path);

		if (input == null) {
			return "Source code is not available at " + path;
		}

		try {
			return new String(toByteArray(input), "UTF-8").replace("\t", "    "); // Tabs are in HTML <pre> presented as 8 spaces, which is too much.
		}
		catch (IOException e) {
			throw new FacesException(String.format(ERROR_LOADING_PAGE_SOURCE, path), e);
		}
	}

	// Getters/setters ------------------------------------------------------------------------------------------------

	public String getTitle() {
		return title;
	}

	public String getViewId() {
		return viewId;
	}

	public String getDescription() {
		return description;
	}

	public List<Source> getSources() {
		return sources;
	}

	public Documentation getDocumentation() {
		return documentation;
	}

	// Object overrides -----------------------------------------------------------------------------------------------

	@Override
	public boolean equals(Object other) {
		return (other instanceof Page) && (title != null)
			? title.equals(((Page) other).title)
			: (other == this);
	}

	@Override
	public int hashCode() {
		return (title != null)
			? (getClass().hashCode() + title.hashCode())
			: super.hashCode();
	}

	@Override
	public String toString() {
		return String.format("Page[title=%s,viewId=%s]", title, viewId);
	}

	// Nested classes -------------------------------------------------------------------------------------------------

	/**
	 * This class represents a source code snippet associated with the current page.
	 *
	 * @author Bauke Scholtz
	 */
	public static class Source implements Serializable {

		// Constants --------------------------------------------------------------------------------------------------

		private static final long serialVersionUID = 1L;

		// Properties -------------------------------------------------------------------------------------------------

		private String title;
		private String type;
		private String code;

		// Contructors ------------------------------------------------------------------------------------------------

		public Source(String title, String type, String code) {
			this.title = title;
			this.type = type;
			this.code = code;
		}

		// Getters/setters --------------------------------------------------------------------------------------------

		public String getTitle() {
			return title;
		}

		public String getType() {
			return type;
		}

		public String getCode() {
			return code;
		}

	}

	/**
	 * This class represents the API and VDL documentation paths associated with the current page.
	 *
	 * @author Bauke Scholtz
	 */
	public static class Documentation implements Serializable {

		// Constants --------------------------------------------------------------------------------------------------

		private static final long serialVersionUID = 1L;

		// Properties -------------------------------------------------------------------------------------------------

		private List<String> api;
		private List<String> src;
		private List<String> vdl;
		private List<String> js;

		// Contructors ------------------------------------------------------------------------------------------------

		public Documentation(List<String> api, List<String> vdl, List<String> js) {
			this.api = api.isEmpty() ? api : asList(api.get(0));
			src = api;
			this.vdl = vdl;
			this.js = js;
		}

		// Getters/setters --------------------------------------------------------------------------------------------

		public List<String> getApi() {
			return api;
		}

		public List<String> getSrc() {
			return src;
		}

		public List<String> getVdl() {
			return vdl;
		}

		public List<String> getJs() {
			return js;
		}

	}

}