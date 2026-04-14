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

# Check for ansi2txt utility and use it if available to strip ANSI color codes
if command -v ansi2txt &> /dev/null; then
    LOG_CONTENT=$(ansi2txt < "${LOG_FILE}" | tr -d '\000')
else
    echo "Warning: ansi2txt utility not found. Proceeding without ANSI color code stripping." >&2
    echo "Install colorized-logs package (Fedora) for better parsing reliability." >&2
    LOG_CONTENT=$(tr -d '\000' < "${LOG_FILE}")
fi

# Extract the Results section from the log
RESULTS_SECTION=$(echo "${LOG_CONTENT}" | sed -n '/^\[INFO\] Results:/,/^\[INFO\] --------/p' | tail -n +2)

# Extract test statistics line
STATS_LINE=$(echo "${RESULTS_SECTION}" | grep -E "Tests run:" | tail -n 1)

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

SUMMARY="${TEST_TYPE} tests ${STATUS} ${EMOJI}\n"
SUMMARY="${SUMMARY}${STATS_LINE}\n"

if [ "${FAILURES}" -gt 0 ] || [ "${ERRORS}" -gt 0 ]; then
    SUMMARY="${SUMMARY}\nFailed tests:\n"
    
    FAILURE_SECTION=$(echo "${RESULTS_SECTION}" | sed -n '/^\[ERROR\] Failures:/,/^\[INFO\]$/p')
    
    # Parse failed test names (format: io.cryostat.FooTest.testBar)
    # Match lines that look like fully qualified test names (contain at least two dots for package.Class.method)
    FAILED_TESTS=$(echo "${FAILURE_SECTION}" | grep -E '^\[ERROR\] [a-zA-Z][a-zA-Z0-9._]*\.[a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_]*' | sed 's/^\[ERROR\] //')
    
    # Group by class
    CURRENT_CLASS=""
    while IFS= read -r test_name; do
        if [ -n "${test_name}" ]; then
            # Extract class name (everything before the last dot)
            CLASS_NAME=$(echo "${test_name}" | sed 's/\.[^.]*$//')
            # Extract method name (everything after the last dot)
            METHOD_NAME=$(echo "${test_name}" | sed 's/.*\.//')
            
            if [ "${CLASS_NAME}" != "${CURRENT_CLASS}" ]; then
                SUMMARY="${SUMMARY}- ${CLASS_NAME}\n"
                CURRENT_CLASS="${CLASS_NAME}"
            fi
            SUMMARY="${SUMMARY}  - ${METHOD_NAME}\n"
        fi
    done <<< "${FAILED_TESTS}"
fi

if [ "${FLAKES}" -gt 0 ]; then
    SUMMARY="${SUMMARY}\nFlaky tests:\n"
    
    FLAKES_SECTION=$(echo "${RESULTS_SECTION}" | sed -n '/^\[WARNING\] Flakes:/,/^\[INFO\]$/p')
    
    # Match lines that look like fully qualified test names (contain at least two dots for package.Class.method)
    FLAKY_TESTS=$(echo "${FLAKES_SECTION}" | grep -E '^\[WARNING\] [a-zA-Z][a-zA-Z0-9._]*\.[a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_]*' | sed 's/^\[WARNING\] //')
    
    # Group by class
    CURRENT_CLASS=""
    while IFS= read -r test_name; do
        if [ -n "${test_name}" ]; then
            # Extract class name (everything before the last dot)
            CLASS_NAME=$(echo "${test_name}" | sed 's/\.[^.]*$//')
            # Extract method name (everything after the last dot)
            METHOD_NAME=$(echo "${test_name}" | sed 's/.*\.//')
            
            if [ "${CLASS_NAME}" != "${CURRENT_CLASS}" ]; then
                SUMMARY="${SUMMARY}- ${CLASS_NAME}\n"
                CURRENT_CLASS="${CLASS_NAME}"
            fi
            SUMMARY="${SUMMARY}  - ${METHOD_NAME}\n"
        fi
    done <<< "${FLAKY_TESTS}"
fi

echo -e "${SUMMARY}"

if [ "${FAILURES}" -gt 0 ] || [ "${ERRORS}" -gt 0 ]; then
    exit 1
else
    exit 0
fi