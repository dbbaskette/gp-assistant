#!/bin/bash

# Greenplum Assistant Run Script
# Usage: ./run.sh [options]
# Options:
#   -b, --build     Build the project before running
#   -c, --clean     Clean build (mvn clean compile)
#   -h, --help      Show this help message

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Default options
BUILD=false
CLEAN=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -b|--build)
            BUILD=true
            shift
            ;;
        -c|--clean)
            BUILD=true
            CLEAN=true
            shift
            ;;
        -h|--help)
            echo "Greenplum Assistant Run Script"
            echo ""
            echo "Usage: ./run.sh [options]"
            echo ""
            echo "Options:"
            echo "  -b, --build     Build the project before running (mvn compile)"
            echo "  -c, --clean     Clean build before running (mvn clean compile)"
            echo "  -h, --help      Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./run.sh                # Run without building"
            echo "  ./run.sh -b             # Build and run"
            echo "  ./run.sh -c             # Clean build and run"
            echo ""
            echo "Environment Variables Required:"
            echo "  OPENAI_API_KEY          Your OpenAI API key"
            echo ""
            echo "Optional Environment Variables:"
            echo "  MCP_CLIENT_ENABLED      Enable MCP client (default: false)"
            echo "  DOCS_INGEST_ON_STARTUP  Ingest docs on startup (default: true)"
            exit 0
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            echo "Use -h or --help for usage information"
            exit 1
            ;;
    esac
done

# Check if OPENAI_API_KEY is set
if [ -z "$OPENAI_API_KEY" ]; then
    echo -e "${RED}Error: OPENAI_API_KEY environment variable is not set${NC}"
    echo "Please set it with: export OPENAI_API_KEY=your-api-key"
    exit 1
fi

# Build if requested
if [ "$BUILD" = true ]; then
    if [ "$CLEAN" = true ]; then
        echo -e "${BLUE}==> Running clean build...${NC}"
        ./mvnw clean compile -DskipTests
    else
        echo -e "${BLUE}==> Building project...${NC}"
        ./mvnw compile -DskipTests
    fi
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Build successful${NC}"
    else
        echo -e "${RED}✗ Build failed${NC}"
        exit 1
    fi
    echo ""
fi

# Display configuration
echo -e "${BLUE}==> Starting Greenplum Assistant${NC}"
echo -e "Version: Spring Boot 3.5.6, Spring AI 1.1.0-SNAPSHOT"
echo -e "MCP Client: ${MCP_CLIENT_ENABLED:-false}"
echo -e "Ingest on Startup: ${DOCS_INGEST_ON_STARTUP:-true}"
echo ""

# Run the application
echo -e "${GREEN}==> Running application...${NC}"
./mvnw spring-boot:run
