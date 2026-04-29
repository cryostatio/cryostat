#!/usr/bin/env bash

# Parse Maven Surefire/Failsafe test results and generate a summary
# Usage: parse-test-results.sh <test-log-file> <test-type>
# Example: parse-test-results.sh test.log "Unit"

set -e

LOG_FILE="${1}"
TEST_TYPE="${2}"  # "Unit" or "Integration"

if [ ! -f "${LOG_FILE}" ]; then
    echo "Error: Log file not found: ${LOG_FILE}"
    exit 1
fi

# Create a temporary file for processed log
TEMP_LOG=$(mktemp)

function cleanup() {
    set +e
    rm -f "${TEMP_LOG}"
    rm -f "${TEMP_RESULTS}"
    rm -f "${TEMP_FAILURES}"
    rm -f "${TEMP_FLAKES}"
    exit 0
}
trap cleanup EXIT

# Check for ansi2txt utility and use it if available to strip ANSI color codes
# Always use tr to remove null bytes which can cause issues
if command -v ansi2txt &> /dev/null; then
    ansi2txt < "${LOG_FILE}" | tr -d '\000' > "${TEMP_LOG}"
else
    echo "Warning: ansi2txt utility not found. Proceeding without ANSI color code stripping." >&2
    echo "Install colorized-logs package (Fedora) for better parsing reliability." >&2
    tr -d '\000' < "${LOG_FILE}" > "${TEMP_LOG}"
fi

# Extract the Results section from the log to another temp file
TEMP_RESULTS=$(mktemp)
sed -n '/^\[INFO\] Results:/,/^\[INFO\] --------/p' "${TEMP_LOG}" | tail -n +2 > "${TEMP_RESULTS}"

# Extract test statistics line
STATS_LINE=$(grep -E "Tests run:" "${TEMP_RESULTS}" | tail -n 1)

if [ -z "${STATS_LINE}" ]; then
    echo "Error: Could not find test statistics in log file"
    exit 1
fi

# Strip the [INFO], [WARNING], or [ERROR] prefix from the stats line
STATS_LINE=$(echo "${STATS_LINE}" | sed 's/^\[\(INFO\|WARNING\|ERROR\)\] //')

# Extract statistics
TESTS_RUN=$(echo "${STATS_LINE}" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')
FAILURES=$(echo "${STATS_LINE}" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')
ERRORS=$(echo "${STATS_LINE}" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')
SKIPPED=$(echo "${STATS_LINE}" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p')
FLAKES=$(echo "${STATS_LINE}" | sed -n 's/.*Flakes: \([0-9]*\).*/\1/p')
# Default flakes to 0 if not present
FLAKES=${FLAKES:-0}

if [ "${FAILURES}" -gt 0 ] || [ "${ERRORS}" -gt 0 ]; then
    STATUS="failed"
    EMOJI="❌"
elif [ "${FLAKES}" -gt 0 ]; then
    STATUS="pass with flaky tests"
    EMOJI="⚠️"
else
    STATUS="pass"
    EMOJI="✅"
fi

echo "${TEST_TYPE} tests ${STATUS} ${EMOJI}"
echo "${STATS_LINE}"

if [ "${FAILURES}" -gt 0 ] || [ "${ERRORS}" -gt 0 ]; then
    echo ""
    echo "Failed tests:"
    
    # Extract failure section to temp file
    TEMP_FAILURES=$(mktemp)
    sed -n '/^\[ERROR\] Failures:/,/^\[INFO\]$/p' "${TEMP_RESULTS}" > "${TEMP_FAILURES}"
    
    # Parse failed test names (format: io.cryostat.FooTest.testBar)
    # Match lines that look like fully qualified test names (contain at least two dots for package.Class.method)
    CURRENT_CLASS=""
    while IFS= read -r line; do
        if echo "${line}" | grep -qE '^\[ERROR\] [a-zA-Z][a-zA-Z0-9._]*\.[a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_]*'; then
            test_name=$(echo "${line}" | sed 's/^\[ERROR\] //')
            # Extract class name (everything before the last dot)
            CLASS_NAME=$(echo "${test_name}" | sed 's/\.[^.]*$//')
            # Extract method name (everything after the last dot)
            METHOD_NAME=$(echo "${test_name}" | sed 's/.*\.//')
            
            if [ "${CLASS_NAME}" != "${CURRENT_CLASS}" ]; then
                echo "- ${CLASS_NAME}"
                CURRENT_CLASS="${CLASS_NAME}"
            fi
            echo "  - ${METHOD_NAME}"
        fi
    done < "${TEMP_FAILURES}"
fi

if [ "${FLAKES}" -gt 0 ]; then
    echo ""
    echo "Flaky tests:"
    
    # Extract flakes section to temp file
    TEMP_FLAKES=$(mktemp)
    sed -n '/^\[WARNING\] Flakes:/,/^\[INFO\]$/p' "${TEMP_RESULTS}" > "${TEMP_FLAKES}"
    
    # Match lines that look like fully qualified test names (contain at least two dots for package.Class.method)
    CURRENT_CLASS=""
    while IFS= read -r line; do
        if echo "${line}" | grep -qE '^\[WARNING\] [a-zA-Z][a-zA-Z0-9._]*\.[a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_]*'; then
            test_name=$(echo "${line}" | sed 's/^\[WARNING\] //')
            # Extract class name (everything before the last dot)
            CLASS_NAME=$(echo "${test_name}" | sed 's/\.[^.]*$//')
            # Extract method name (everything after the last dot)
            METHOD_NAME=$(echo "${test_name}" | sed 's/.*\.//')
            
            if [ "${CLASS_NAME}" != "${CURRENT_CLASS}" ]; then
                echo "- ${CLASS_NAME}"
                CURRENT_CLASS="${CLASS_NAME}"
            fi
            echo "  - ${METHOD_NAME}"
        fi
    done < "${TEMP_FLAKES}"
fi

if [ "${FAILURES}" -gt 0 ] || [ "${ERRORS}" -gt 0 ]; then
    exit 1
else
    exit 0
fi