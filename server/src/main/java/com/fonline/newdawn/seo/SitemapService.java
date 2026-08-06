package com.fonline.newdawn.seo;

import com.fonline.newdawn.config.AppProperties;
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

    public SitemapService(JdbcClient jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public String render() {
        List<WikiUrl> wikiUrls = jdbc.sql("""
                SELECT slug, updated_at
                FROM wiki_page
                WHERE status = 'PUBLISHED' AND published_revision_id IS NOT NULL
                ORDER BY slug
                LIMIT :limit
                """).param("limit", MAX_WIKI_URLS)
                .query((rs, row) -> new WikiUrl(
                        rs.getString("slug"), rs.getTimestamp("updated_at").toInstant()))
                .list();

        return render(properties.publicBaseUrl(), wikiUrls);
    }

    static String render(String publicBaseUrl, List<WikiUrl> wikiUrls) {
        String baseUrl = publicBaseUrl.replaceAll("/+$", "");
        StringWriter output = new StringWriter();

        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.setDefaultNamespace(SITEMAP_NAMESPACE);
            xml.writeStartElement(SITEMAP_NAMESPACE, "urlset");
            xml.writeDefaultNamespace(SITEMAP_NAMESPACE);

            writeUrl(xml, baseUrl + "/", null);
            writeUrl(xml, baseUrl + "/wiki", null);
            writeUrl(xml, baseUrl + "/download", null);
            for (WikiUrl wikiUrl : wikiUrls) {
                writeUrl(xml, baseUrl + "/wiki/" + wikiUrl.slug(), wikiUrl.lastModified());
            }

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
