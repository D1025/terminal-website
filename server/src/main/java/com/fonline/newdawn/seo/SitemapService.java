package com.fonline.newdawn.seo;

import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.configuration.TimedContentAccessService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

@Service
public class SitemapService {
    private static final String SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final int MAX_WIKI_URLS = 49_997;

    private final JdbcClient jdbc;
    private final AppProperties properties;
    private final TimedContentAccessService timedAccess;

    public SitemapService(JdbcClient jdbc, AppProperties properties, TimedContentAccessService timedAccess) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.timedAccess = timedAccess;
    }

    public String render() {
        boolean wikiPublic = timedAccess.isWikiPublic();
        boolean downloadPublic = timedAccess.isDownloadPublic();
        List<WikiUrl> wikiUrls = wikiPublic ? jdbc.sql("""
                SELECT slug, updated_at
                FROM wiki_page
                WHERE status = 'PUBLISHED' AND published_revision_id IS NOT NULL
                ORDER BY slug
                LIMIT :limit
                """).param("limit", MAX_WIKI_URLS)
                .query((rs, row) -> new WikiUrl(
                        rs.getString("slug"), rs.getTimestamp("updated_at").toInstant()))
                .list() : List.of();

        return render(properties.publicBaseUrl(), wikiUrls, wikiPublic, downloadPublic);
    }

    static String render(String publicBaseUrl, List<WikiUrl> wikiUrls) {
        return render(publicBaseUrl, wikiUrls, true, true);
    }

    static String render(String publicBaseUrl, List<WikiUrl> wikiUrls,
                         boolean wikiPublic, boolean downloadPublic) {
        String baseUrl = publicBaseUrl.replaceAll("/+$", "");
        StringWriter output = new StringWriter();

        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.setDefaultNamespace(SITEMAP_NAMESPACE);
            xml.writeStartElement(SITEMAP_NAMESPACE, "urlset");
            xml.writeDefaultNamespace(SITEMAP_NAMESPACE);

            writeUrl(xml, baseUrl + "/", null);
            if (wikiPublic) {
                writeUrl(xml, baseUrl + "/wiki", null);
                for (WikiUrl wikiUrl : wikiUrls) {
                    writeUrl(xml, baseUrl + "/wiki/" + wikiUrl.slug(), wikiUrl.lastModified());
                }
            }
            if (downloadPublic) writeUrl(xml, baseUrl + "/download", null);

            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return output.toString();
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("Could not generate sitemap XML.", exception);
        }
    }

    private static void writeUrl(XMLStreamWriter xml, String location, Instant lastModified)
            throws XMLStreamException {
        xml.writeStartElement(SITEMAP_NAMESPACE, "url");
        xml.writeStartElement(SITEMAP_NAMESPACE, "loc");
        xml.writeCharacters(location);
        xml.writeEndElement();
        if (lastModified != null) {
            xml.writeStartElement(SITEMAP_NAMESPACE, "lastmod");
            xml.writeCharacters(lastModified.toString());
            xml.writeEndElement();
        }
        xml.writeEndElement();
    }

    record WikiUrl(String slug, Instant lastModified) {}
}
