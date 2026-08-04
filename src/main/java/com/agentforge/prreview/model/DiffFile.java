package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Represents a single file changed in a pull request.
 * Parsed from the GitHub unified diff format.
 */
@Data
@Builder
public class DiffFile {
    /** File path relative to repository root */
    private String filename;
    /** Detected programming language based on file extension */
    private String language;
    /** Lines added in this file (without the leading '+') */
    private List<String> addedLines;
    /** Lines removed in this file (without the leading '-') */
    private List<String> removedLines;
    /** Full raw unified diff for this file */
    private String rawDiff;
    private int linesAdded;
    private int linesRemoved;
    private ChangeType changeType;

    public enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED }
}
