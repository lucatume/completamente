#!/usr/bin/env python3
"""
Mock infill server that listens on localhost:8012/infill and logs requests/responses.
Uses the 'file' key in extra_input to determine which pre-planned response to return.
"""

import json
import logging
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import sys

# Setup logging
log_file = Path(__file__).parent / "mock_infill_server.log"
logging.basicConfig(
    filename=log_file,
    level=logging.INFO,
    format='%(message)s'
)

# Pre-planned responses mapped by file name
RESPONSES = {
    "empty.ts": [],
    "large.ts": {
        "line_1_col_10": {
            "content": "greeting"
        },
        "line_10_col_0": {
            "content": "// Function to calculate sum\nfunction sum(a: number, b: number): number {\n    return a + b;\n}"
        },
        "line_10_col_5": {
            "content": "// Calculate factorial\nfunction factorial(n: number): number {"
        },
        "line_50_col_20": {
            "content": " {\n        console.log('Processing item:', item);\n        results.push(item * 2);\n    }"
        },
        "line_50_col_0": {
            "content": ""
        },
        "line_100_col_15": {
            "content": " {\n        if (count > 0) {\n            await processData(data);\n        }\n    }"
        },
        "default": {
            "content": "// TODO: complete this\n"
        }
    },
    "fim_render_dedup": {
        "empty_first_repeating": {
            "content": "\nfunction greeting(): string {"
        },
        "repeats_suffix": {
            "content": "greeting(): string {"
        },
        "normal_multiline": {
            "content": "const result = {\n    value: 42,\n    valid: true\n};"
        },
        "whitespace_only": {
            "content": "   \n  \n   "
        },
        "trailing_newlines": {
            "content": "console.log('test');\n\n\n"
        }
    }
}

class MockInfillHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        """Handle POST requests to /infill endpoint."""
        if self.path != '/infill':
            self.send_error(404)
            return

        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)

        try:
            request_data = json.loads(body)
        except json.JSONDecodeError:
            self.send_error(400, "Invalid JSON")
            return

        # Extract test identifier from extra_input
        extra_input = request_data.get('extra_input', [])
        test_id = extra_input[0] if extra_input else 'unknown'

        # Log the request
        log_entry = {
            'test_id': test_id,
            'request': {
                'input_prefix': request_data.get('input_prefix', ''),
                'prompt': request_data.get('prompt', ''),
                'input_suffix': request_data.get('input_suffix', ''),
            }
        }

        # Determine response
        file_key = test_id.split('::')[0] if '::' in test_id else test_id
        case_key = test_id.split('::')[1] if '::' in test_id else 'default'
        response_data = None

        if file_key in RESPONSES:
            file_responses = RESPONSES[file_key]
            if isinstance(file_responses, dict):
                # Look up by test_id or use default
                if test_id in file_responses:
                    response_data = file_responses[test_id]
                elif case_key in file_responses:
                    response_data = file_responses[case_key]
                else:
                    response_data = file_responses.get('default', {'content': ''})
            elif isinstance(file_responses, list):
                # For empty.ts, return empty content
                response_data = {'content': ''}
        else:
            response_data = {'content': ''}

        # Extract content and build full response with timing info
        content_value = response_data.get('content', '')
        full_response = {
            "content": content_value,
            "timings/prompt_n": 50,
            "timings/prompt_ms": "10.5",
            "timings/prompt_per_second": "4761.9",
            "timings/predicted_n": 20,
            "timings/predicted_ms": "50.2",
            "timings/predicted_per_second": "398.4",
            "tokens_cached": 100,
            "timings/truncated": False,
            "result": content_value  # For backward compatibility
        }

        log_entry['response'] = full_response
        logging.info(json.dumps(log_entry))

        # Send response
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(full_response).encode())

    def log_message(self, format, *args):
        """Suppress default logging."""
        pass

def main():
    server_address = ('localhost', 8012)
    httpd = HTTPServer(server_address, MockInfillHandler)
    print(f"Mock infill server listening on http://localhost:8012/infill")
    print(f"Logging requests to {log_file}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped")
        httpd.server_close()

if __name__ == '__main__':
    main()
