FROM python:3.11-slim

WORKDIR /app

# Install dependencies
RUN pip install --no-cache-dir requests

# Copy test client script
COPY test-client.py /app/

# Make executable
RUN chmod +x /app/test-client.py

# Run test client
CMD ["python", "/app/test-client.py"]

