package com.fonline.newdawn.seo;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapServiceTest {
    @Test
    void rendersStaticRoutesAndPublishedWikiPagesAsValidXml() throws Exception {
        String xml = SitemapService.render("https://fonline-nd.com/", List.of(
                new SitemapService.WikiUrl("getting-started", Instant.parse("2026-08-06T10:15:30Z")),
                new SitemapService.WikiUrl("world-map", Instant.parse("2026-08-06T11:20:00Z"))
        ));

        var documentFactory = DocumentBuilderFactory.newInstance();
        documentFactory.setNamespaceAware(true);
        var document = documentFactory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(document.getDocumentElement().getNamespaceURI())
                .isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        assertThat(document.getElementsByTagNameNS("*", "loc").getLength()).isEqualTo(5);
        assertThat(xml).contains("<loc>https://fonline-nd.com/wiki/getting-started</loc>");
        assertThat(xml).contains("<lastmod>2026-08-06T10:15:30Z</lastmod>");
        assertThat(xml).doesNotContain("fonline-nd.com//wiki");
    }
}
