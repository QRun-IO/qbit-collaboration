// Disposable loopback fixture only; never deploy this fault-injection router.
import http from 'node:http';

const [first, second, listen] = process.argv.slice(2).map(Number);
if (![first, second, listen].every(port => Number.isInteger(port) && port > 0 && port < 65536)) {
  throw new Error('Expected first-node, second-node and router ports');
}
const firstStreams = new Set();
const server = http.createServer((request, response) => {
  if (request.method === 'POST' && request.url === '/__acceptance/disconnect-first') {
    const disconnected = firstStreams.size;
    for (const stream of firstStreams) stream.destroy();
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ disconnected }));
    return;
  }
  const isSecond = /(?:^|;\s*)presence_node=second(?:;|$)/.test(request.headers.cookie ?? '');
  const port = isSecond ? second : first;
  const upstream = http.request({
    hostname: '127.0.0.1', port, path: request.url, method: request.method,
    headers: { ...request.headers, host: `127.0.0.1:${port}` },
  }, incoming => {
    response.writeHead(incoming.statusCode, {
      ...incoming.headers, 'x-acceptance-node': isSecond ? 'second' : 'first',
    });
    if (!isSecond && incoming.headers['content-type']?.includes('text/event-stream')) {
      firstStreams.add(response);
    }
    incoming.pipe(response);
  });
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502);
    response.end('Acceptance node unavailable');
  });
  response.on('close', () => {
    firstStreams.delete(response);
    upstream.destroy();
  });
  request.pipe(upstream);
});
server.listen(listen, '127.0.0.1', () => console.log(`PRESENCE_ROUTER_READY ${listen}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
