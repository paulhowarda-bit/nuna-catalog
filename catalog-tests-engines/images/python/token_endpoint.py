"""The identity provider's token endpoint for the audit table writer in the engine matrix (EngineMatrixTest#auditTables).

The writer signs in with the OAuth 2 client-credentials grant, as in a deployment; the matrix's issuer is only a key
the harness holds, so this answers every token request with the token the harness signed for the writer, in
WRITER_TOKEN. Iceberg's client takes its expiry from the token itself.
"""
import http.server
import json
import os

TOKEN = os.environ["WRITER_TOKEN"]


class TokenEndpoint(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - the handler's name is http.server's
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        body = json.dumps({"access_token": TOKEN, "token_type": "bearer",
                           "issued_token_type": "urn:ietf:params:oauth:token-type:access_token"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


http.server.HTTPServer(("", 8080), TokenEndpoint).serve_forever()
