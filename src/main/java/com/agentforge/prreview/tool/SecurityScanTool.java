package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static security analysis tool.
 * Implements OWASP Top 10 pattern detection on PR diffs.
 *
 * Uses regex-based pattern matching as a fast first pass —
 * the LLM review then catches contextual issues these rules miss.
 */
@Component
@Slf4j
public class SecurityScanTool {

    // Matches unified-diff hunk headers, e.g. "@@ -12,7 +15,9 @@ optional context"
    // Group 2 is the starting line number of the hunk in the NEW (post-change) file.
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    // OWASP A03: Injection patterns
    private static final Pattern SQL_INJECTION = Pattern.compile(
            "\"SELECT.*\"\\s*\\+|\"INSERT.*\"\\s*\\+|\"UPDATE.*\"\\s*\\+|\"DELETE.*\"\\s*\\+",
            Pattern.CASE_INSENSITIVE);

    // OWASP A02: Cryptographic failures
    private static final Pattern WEAK_CRYPTO = Pattern.compile(
            "MD5|SHA-1|DES\\b|RC4|getInstance\\(\"(?:MD5|SHA-1)\"\\)",
            Pattern.CASE_INSENSITIVE);

    // OWASP A05: Security misconfiguration — hardcoded secrets
    private static final Pattern HARDCODED_SECRET = Pattern.compile(
            "(password|passwd|pwd|secret|api_key|apikey|token)[A-Za-z0-9_]*\\s*=\\s*\"[^\"]{6,}\"",
            Pattern.CASE_INSENSITIVE);

    // OWASP A01: Missing input sanitisation on REST endpoints
    private static final Pattern UNVALIDATED_INPUT = Pattern.compile(
            "@(RequestParam|PathVariable|RequestBody)(?!.*@Valid)");

    private record SecurityRule(Pattern pattern, String title, String description,
                                ReviewComment.CommentSeverity severity, String owaspRef) {}

    private final List<SecurityRule> rules = List.of(
            new SecurityRule(SQL_INJECTION,
                    "Potential SQL injection",
                    "Potential SQL injection — use parameterised queries or JPA criteria instead of string concatenation.",
                    ReviewComment.CommentSeverity.CRITICAL, "OWASP A03:2021 – Injection"),
            new SecurityRule(WEAK_CRYPTO,
                    "Weak cryptographic algorithm",
                    "Weak cryptographic algorithm detected. Use SHA-256 or stronger. MD5 and SHA-1 are broken for security use.",
                    ReviewComment.CommentSeverity.HIGH, "OWASP A02:2021 – Cryptographic Failures"),
            new SecurityRule(HARDCODED_SECRET,
                    "Potential hardcoded secret",
                    "Potential hardcoded secret detected. Use environment variables or a secrets manager (Azure Key Vault, AWS Secrets Manager).",
                    ReviewComment.CommentSeverity.CRITICAL, "OWASP A02:2021 – Cryptographic Failures"),
            new SecurityRule(UNVALIDATED_INPUT,
                    "Unvalidated request input",
                    "Request parameter without @Valid annotation — add Bean Validation constraints to prevent invalid input reaching business logic.",
                    ReviewComment.CommentSeverity.MEDIUM, "OWASP A03:2021 – Injection")
    );

    public List<ReviewComment> scan(List<DiffFile> changedFiles) {
        List<ReviewComment> comments = new ArrayList<>();

        for (DiffFile file : changedFiles) {
            // DiffFile.filename is the path relative to repo root
            if (!file.getFilename().endsWith(".java")) continue;

            List<AddedLine> addedLines = extractAddedLinesWithRealLineNumbers(file.getRawDiff());
            for (AddedLine added : addedLines) {
                for (SecurityRule rule : rules) {
                    if (rule.pattern().matcher(added.code()).find()) {
                        comments.add(ReviewComment.builder()
                                .filename(file.getFilename())
                                .lineNumber(added.lineNumber())
                                .severity(rule.severity())
                                .category(ReviewComment.CommentCategory.SECURITY)
                                .title(rule.title())
                                .body(formatComment(rule, added.code()))
                                .build());
                        log.debug("Security issue in {} line {}: {}",
                                file.getFilename(), added.lineNumber(), rule.owaspRef());
                    }
                }
            }
        }

        return comments;
    }

    private record AddedLine(String code, int lineNumber) {}

    /**
     * Walks the unified diff for a single file and computes the TRUE target-file
     * line number for every added ("+") line, by tracking hunk headers
     * ("@@ -a,b +c,d @@") rather than using a simple running counter over all
     * added lines. This is required because a file can have multiple hunks,
     * each starting at a different line number in the new file — a flat index
     * over addedLines would misattribute lines for any file with >1 hunk.
     */
    private List<AddedLine> extractAddedLinesWithRealLineNumbers(String rawDiff) {
        List<AddedLine> result = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) return result;

        int currentNewLine = -1; // -1 = not yet inside a hunk

        for (String line : rawDiff.split("\\n")) {
            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.find()) {
                currentNewLine = Integer.parseInt(hunkMatcher.group(2));
                continue;
            }

            if (currentNewLine < 0) {
                continue; // haven't seen a hunk header yet (file header lines, etc.)
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                result.add(new AddedLine(line.substring(1), currentNewLine));
                currentNewLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // removed line — does not exist in the new file, doesn't advance currentNewLine
            } else {
                // context line — exists in both old and new file
                currentNewLine++;
            }
        }

        return result;
    }

    private String formatComment(SecurityRule rule, String code) {
        return """
                🔒 **Security Issue** — %s

                **Reference:** %s

                **Detected in:** `%s`
                """.formatted(rule.description(), rule.owaspRef(), code.trim());
    }
}
