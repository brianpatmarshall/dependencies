package com.boeing.depgraph.backend.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue is data, and data with no test rots.
 *
 * <p>None of this needs a database. It checks the things that go wrong when an entry is
 * added in a hurry: a duplicate id, a parameter the Cypher never uses, a parameter the
 * Cypher uses that nobody declared, and a bridge query that forgot to say it needs the
 * code plane - which would show an empty table rather than an explanation after a
 * build-plane-only scan.
 */
class CannedQueriesTest {

    private static final Pattern PARAM = Pattern.compile("\\$([a-zA-Z][a-zA-Z0-9_]*)");

    static List<CannedQuery> queries() {
        return CannedQueries.all();
    }

    @Test
    void catalogueIsNotEmpty() {
        assertThat(CannedQueries.all()).hasSizeGreaterThan(20);
    }

    @Test
    void idsAreUnique() {
        List<String> ids = CannedQueries.all().stream().map(CannedQuery::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyCategoryHasAtLeastOneQuery() {
        Set<CannedQuery.Category> used = CannedQueries.all().stream()
            .map(CannedQuery::category)
            .collect(Collectors.toSet());
        assertThat(used).containsExactlyInAnyOrder(CannedQuery.Category.values());
    }

    @ParameterizedTest
    @MethodSource("queries")
    void declaredParametersMatchTheCypher(CannedQuery query) {
        Set<String> inCypher = PARAM.matcher(query.cypher()).results()
            .map(result -> result.group(1))
            .collect(Collectors.toSet());
        Set<String> declared = query.params().stream()
            .map(CannedQuery.Param::name)
            .collect(Collectors.toSet());

        assertThat(declared)
            .as("%s declares parameters its Cypher does not use", query.id())
            .containsExactlyInAnyOrderElementsOf(inCypher);
    }

    @ParameterizedTest
    @MethodSource("queries")
    void everyParameterHasAWorkingDefault(CannedQuery query) {
        for (CannedQuery.Param param : query.params()) {
            assertThat(param.defaultValue())
                .as("%s parameter %s has no default, so clicking it would do nothing",
                    query.id(), param.name())
                .isNotBlank();
        }
    }

    @ParameterizedTest
    @MethodSource("queries")
    void queriesTouchingTheCodePlaneSaySo(CannedQuery query) {
        boolean touchesCode = query.cypher().contains(":Type")
            || query.cypher().contains("USES_CODE")
            || query.cypher().contains("m.built");
        if (touchesCode) {
            assertThat(query.needsCodePlane())
                .as("%s reads the code plane but is not flagged as needing it", query.id())
                .isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("queries")
    void everyQueryReturnsSomething(CannedQuery query) {
        assertThat(query.cypher()).containsIgnoringCase("RETURN");
        assertThat(query.title()).isNotBlank();
        assertThat(query.question()).endsWith("?");
    }
}
