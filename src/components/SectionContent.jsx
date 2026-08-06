import Countdown from './Countdown';
import info from '../data/info.json';

const contentMap = { overview: info };
const sectionLabels = { overview: 'Overview' };

function renderInline(text) {
    return text
        .split(/(\*\*[^*]+\*\*|\*[^*]+\*)/g)
        .filter(Boolean)
        .map((part, index) => {
            if (part.startsWith('**') && part.endsWith('**')) return <strong key={`${part}-${index}`}>{part.slice(2, -2)}</strong>;
            if (part.startsWith('*') && part.endsWith('*')) return <em key={`${part}-${index}`}>{part.slice(1, -1)}</em>;
            return part;
        });
}

function RichText({ text }) {
    const blocks = text.trim().split(/\n{2,}/);
    return (
        <div className="rich-text">
            {blocks.map((block, blockIndex) => {
                const lines = block.split('\n').filter(Boolean);
                const regularLines = lines.filter(line => !line.startsWith('* '));
                const bulletLines = lines.filter(line => line.startsWith('* '));
                return (
                    <div className="rich-block" key={`${block.slice(0, 20)}-${blockIndex}`}>
                        {regularLines.map((line, lineIndex) => <p key={`${line}-${lineIndex}`}>{renderInline(line)}</p>)}
                        {bulletLines.length > 0 && <ul>{bulletLines.map((line, lineIndex) => <li key={`${line}-${lineIndex}`}>{renderInline(line.slice(2))}</li>)}</ul>}
                    </div>
                );
            })}
        </div>
    );
}

function DataTable({ headers, rows }) {
    return (
        <div className="table-region" role="region" aria-label="Terminal data table" tabIndex="0">
            <table>
                <thead><tr>{headers.map(header => <th key={header} scope="col">{header}</th>)}</tr></thead>
                <tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={`${cell}-${cellIndex}`}>{cell}</td>)}</tr>)}</tbody>
            </table>
        </div>
    );
}

function ContentBody({ data }) {
    return (
        <div className="content-body">
            {data.content && <RichText text={data.content} />}
            {data.images && <div className="content-images">{data.images.map((image, index) => {
                const source = typeof image === 'string' ? image : image.src;
                const alt = typeof image === 'string' ? `Terminal reference ${index + 1}` : image.alt ?? `Terminal reference ${index + 1}`;
                return <img key={source} src={source} alt={alt} loading="lazy" decoding="async" />;
            })}</div>}
            {data.table && <DataTable headers={data.table.headers} rows={data.table.rows} />}
        </div>
    );
}

function LockedContent({ label = 'Section' }) {
    return <div className="access-message" role="status"><span>ACCESS DENIED</span><p>{label} is not available on the public network.</p></div>;
}

function TimedContent({ unlock }) {
    return <div className="access-message"><span>SECTION UNLOCKS IN</span><Countdown target={unlock} /></div>;
}

export default function SectionContent({ section }) {
    const data = contentMap[section];
    if (!data || data.unlockDate === undefined) return <LockedContent label={sectionLabels[section]} />;
    const unlock = data.unlockDate ? new Date(data.unlockDate).getTime() : 0;
    if (unlock > Date.now()) return <TimedContent unlock={unlock} />;
    return (
        <article className="section-article" aria-labelledby="content-heading" tabIndex="0">
            <p className="content-path">PUBLIC / OVERVIEW</p>
            <h2 id="content-heading">{sectionLabels[section] ?? section}</h2>
            <ContentBody data={data} />
        </article>
    );
}
