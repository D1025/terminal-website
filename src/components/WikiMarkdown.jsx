import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

const COLUMN_MARKERS = {
    start: ':::columns',
    left: ':::left',
    right: ':::right',
    end: ':::end'
};

const TABLE_MARKERS = {
    start: ':::table',
    end: ':::endtable'
};

export default function WikiMarkdown({ children = '' }) {
    return (
        <div className="wiki-markdown">
            <WikiBlocks source={String(children)} />
        </div>
    );
}

function WikiBlocks({ source }) {
    const blocks = parseLayoutBlocks(source);
    return blocks.map((block, index) => {
        if (block.type === 'columns') {
            return (
                <div className="wiki-content-columns" key={`columns-${index}`}>
                    <section className="wiki-content-column wiki-content-column--main" aria-label="Main column">
                        <WikiBlocks source={block.left} />
                    </section>
                    <aside className="wiki-content-column wiki-content-column--aside" aria-label="Side column">
                        <WikiBlocks source={block.right} />
                    </aside>
                </div>
            );
        }
        if (block.type === 'table') return <AdvancedTable definition={block.definition} key={`table-${index}`} />;
        return <MarkdownBlock key={`markdown-${index}`}>{block.content}</MarkdownBlock>;
    });
}

function MarkdownBlock({ children }) {
    return <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{children}</ReactMarkdown>;
}

function parseLayoutBlocks(source) {
    const lines = source.replaceAll('\r\n', '\n').split('\n');
    const blocks = [];
    let plainLines = [];
    let activeFence = null;

    function flushPlain() {
        const content = plainLines.join('\n');
        if (content.trim()) blocks.push({ type: 'markdown', content });
        plainLines = [];
    }

    for (let index = 0; index < lines.length;) {
        const nextFence = updateFence(activeFence, lines[index]);
        if (activeFence || nextFence) {
            plainLines.push(lines[index]);
            activeFence = nextFence;
            index += 1;
            continue;
        }

        if (lines[index].trim() !== COLUMN_MARKERS.start || lines[index + 1]?.trim() !== COLUMN_MARKERS.left) {
            if (lines[index].trim() === TABLE_MARKERS.start) {
                const endIndex = findMarker(lines, TABLE_MARKERS.end, index + 1);
                const definition = endIndex === -1 ? null : parseTableDefinition(lines.slice(index + 1, endIndex).join('\n'));
                if (definition) {
                    flushPlain();
                    blocks.push({ type: 'table', definition });
                    index = endIndex + 1;
                    continue;
                }
            }
            plainLines.push(lines[index]);
            index += 1;
            continue;
        }

        const rightIndex = findMarker(lines, COLUMN_MARKERS.right, index + 2);
        const endIndex = rightIndex === -1 ? -1 : findMarker(lines, COLUMN_MARKERS.end, rightIndex + 1);
        if (rightIndex === -1 || endIndex === -1) {
            plainLines.push(lines[index]);
            index += 1;
            continue;
        }

        flushPlain();
        blocks.push({
            type: 'columns',
            left: lines.slice(index + 2, rightIndex).join('\n'),
            right: lines.slice(rightIndex + 1, endIndex).join('\n')
        });
        index = endIndex + 1;
    }

    flushPlain();
    return blocks;
}

function AdvancedTable({ definition }) {
    return (
        <div className="wiki-table-scroll wiki-table-scroll--advanced" role="region" aria-label={definition.caption || 'Advanced data table'} tabIndex="0">
            <table className="wiki-advanced-table">
                {definition.caption && <caption>{definition.caption}</caption>}
                <tbody>
                    {definition.rows.map((row, rowIndex) => (
                        <tr key={`row-${rowIndex}`}>
                            {row.cells.map((cell, cellIndex) => {
                                const Cell = cell.header ? 'th' : 'td';
                                return (
                                    <Cell
                                        className={`wiki-table-cell--${cell.align}`}
                                        colSpan={cell.colSpan}
                                        rowSpan={cell.rowSpan}
                                        scope={cell.header ? (rowIndex === 0 ? 'col' : 'row') : undefined}
                                        key={`cell-${cellIndex}`}
                                    >
                                        <MarkdownBlock>{cell.content || ' '}</MarkdownBlock>
                                    </Cell>
                                );
                            })}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

function parseTableDefinition(source) {
    try {
        const parsed = JSON.parse(source);
        if (!parsed || !Array.isArray(parsed.rows) || !parsed.rows.length) return null;
        const rows = parsed.rows.slice(0, 50).map(row => ({
            cells: (Array.isArray(row?.cells) ? row.cells : []).slice(0, 20).map(cell => ({
                content: String(cell?.content ?? '').slice(0, 20_000),
                header: Boolean(cell?.header),
                colSpan: clampSpan(cell?.colSpan, 12),
                rowSpan: clampSpan(cell?.rowSpan, 50),
                align: ['left', 'center', 'right'].includes(cell?.align) ? cell.align : 'left'
            }))
        })).filter(row => row.cells.length);
        if (!rows.length) return null;
        return { caption: String(parsed.caption ?? '').slice(0, 500), rows };
    } catch {
        return null;
    }
}

function clampSpan(value, maximum) {
    const number = Number.parseInt(value, 10);
    return Number.isFinite(number) ? Math.min(maximum, Math.max(1, number)) : 1;
}

function findMarker(lines, marker, start) {
    let activeFence = null;
    for (let index = start; index < lines.length; index += 1) {
        const nextFence = updateFence(activeFence, lines[index]);
        if (!activeFence && !nextFence && lines[index].trim() === marker) return index;
        activeFence = nextFence;
    }
    return -1;
}

function updateFence(activeFence, line) {
    const match = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (!match) return activeFence;
    if (!activeFence) return match[1];
    return match[1][0] === activeFence[0] && match[1].length >= activeFence.length ? null : activeFence;
}

const markdownComponents = {
    a: ({ href, children }) => <a href={href} rel={href?.startsWith('http') ? 'noreferrer' : undefined}>{children}</a>,
    img: ({ src, alt }) => <img className="wiki-inline-image" src={src} alt={alt ?? ''} loading="lazy" />,
    table: ({ children }) => (
        <div className="wiki-table-scroll" role="region" aria-label="Data table" tabIndex="0">
            <table>{children}</table>
        </div>
    )
};
