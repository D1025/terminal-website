UPDATE wiki_page SET page_type = 'ARTICLE' WHERE page_type <> 'ARTICLE';

DELETE FROM wiki_page_type WHERE key <> 'ARTICLE';
