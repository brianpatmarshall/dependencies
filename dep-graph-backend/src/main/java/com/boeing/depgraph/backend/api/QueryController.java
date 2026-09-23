package com.boeing.depgraph.backend.api;

import com.boeing.depgraph.backend.query.CannedQueries;
import com.boeing.depgraph.backend.query.CannedQuery;
import com.boeing.depgraph.backend.query.QueryResult;
import com.boeing.depgraph.backend.query.QueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queries;

    public QueryController(QueryService queries) {
        this.queries = queries;
    }

    /** The catalogue, grouped, in the order the UI should show it. */
    @GetMapping("/canned")
    public List<Group> canned() {
        Map<CannedQuery.Category, List<CannedQuery>> byCategory = new LinkedHashMap<>();
        for (CannedQuery.Category category : CannedQuery.Category.values()) {
            byCategory.put(category, CannedQueries.all().stream()
                .filter(query -> query.category() == category)
                .toList());
        }
        return byCategory.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> new Group(entry.getKey().name(),
                entry.getKey().displayName(), entry.getValue()))
            .toList();
    }

    public record Group(String id, String title, List<CannedQuery> queries) {}

    @GetMapping("/canned/{id}")
    public CannedQuery one(@PathVariable String id) {
        return CannedQueries.byId(id)
            .orElseThrow(() -> new IllegalArgumentException("No such query: " + id));
    }

    @PostMapping("/canned/{id}")
    public QueryResult run(@PathVariable String id,
                           @RequestBody(required = false) Map<String, Object> params) {
        return queries.runCanned(id, params);
    }

    public record AdHocRequest(String cypher, Map<String, Object> params) {}

    /** Read-only. A write clause comes back as a 400, not a 500. */
    @PostMapping("/execute")
    public QueryResult execute(@RequestBody AdHocRequest request) {
        return queries.runAdHoc(request.cypher(), request.params());
    }
}
