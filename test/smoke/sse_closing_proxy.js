// Proxy that will close any connection for a response with the partial payload "event: end"
// This allows us to treat server send events (SSE) as if the server would close the connection, easing integration with regular HTTP tools
const http = require("http"),
  httpProxy = require("http-proxy"),
  zlib = require("zlib");

const target = process.env.TARGET_BASE_URL || "http://localhost:8000",
  port = process.env.PORT || 3333;

const proxy = httpProxy.createProxyServer({});

proxy.on("proxyReq", function (proxyReq, req, res, options) {
  // Is it wiremock that adds this header? Let's keep it plain text
  proxyReq.setHeader("Accept-Encoding", "");
});

proxy.on("proxyRes", function (proxyRes, req, res) {
  proxyRes.on("data", (d) => {
    if (/event: end/.test(d.toString())) {
      setTimeout(() => res.end(), 10);
    }
  });
});

console.log("Opening on connection", port, "proxying", target);
http
  .createServer(function (req, res) {
    proxy.web(req, res, { target });
  })
  .listen(port);
