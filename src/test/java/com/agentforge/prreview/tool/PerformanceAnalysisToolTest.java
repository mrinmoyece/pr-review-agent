package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceAnalysisToolTest {

    private PerformanceAnalysisTool tool;

    @BeforeEach
    void setUp() { tool = new PerformanceAnalysisTool(); }

    private DiffFile javaFile(List<String> addedLines) {
        return DiffFile.builder()
                .filename("Service.java").language("java")
                .addedLines(addedLines).removedLines(List.of()).rawDiff("").build();
    }

    @Test
    void givenUnboundedFindAll_whenAnalysed_thenFlagged() {
        DiffFile file = javaFile(List.of("List<User> all = userRepository.findAll();"));
        List<ReviewComment> comments = tool.analyse(List.of(file));
        assertThat(comments).anyMatch(c ->
                c.getCategory() == ReviewComment.CommentCategory.PERFORMANCE &&
                c.getSeverity().ordinal() <= ReviewComment.CommentSeverity.HIGH.ordinal());
    }

    @Test
    void givenCleanCode_whenAnalysed_thenEmpty() {
        DiffFile file = javaFile(List.of(
                "Page<User> users = userRepository.findAll(pageable);",
                "return users.getContent();"));
        assertThat(tool.analyse(List.of(file))).isEmpty();
    }

    @Test
    void givenNonJavaFile_whenAnalysed_thenSkipped() {
        DiffFile file = DiffFile.builder()
                .filename("script.py").language("python")
                .addedLines(List.of("result = db.findAll()"))
                .removedLines(List.of()).rawDiff("").build();
        // Should not crash or produce Java-specific findings on Python files
        assertThat(tool.analyse(List.of(file))).isEmpty();
    }
}
