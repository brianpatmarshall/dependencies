package com.boeing.depgraph.backend.api;

import com.boeing.depgraph.backend.scan.ScanService;
import com.boeing.depgraph.backend.scan.ScanSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private final ScanService scans;

    public ScanController(ScanService scans) {
        this.scans = scans;
    }

    public record ScanRequest(String path, boolean buildPlaneOnly) {}

    @PostMapping
    public ScanSummary scan(@RequestBody ScanRequest request) {
        return scans.scan(request.path(), request.buildPlaneOnly(), message -> {});
    }

    /** The last scan, or 204 if this instance has not run one. */
    @GetMapping("/last")
    public ScanSummary last() {
        return scans.lastScan();
    }

    /**
     * The same scan, narrating as it goes.
     *
     * <p>A full estate scan writes a few hundred thousand relationships, which is long
     * enough that a spinner with no detail is indistinguishable from a hang. Each step
     * reports as it starts.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String path,
                             @RequestParam(defaultValue = "false") boolean buildPlaneOnly) {
        SseEmitter emitter = new SseEmitter(0L);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ScanSummary summary = scans.scan(path, buildPlaneOnly, message -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(message));
                    } catch (Exception e) {
                        // The client went away mid-scan. The scan itself is worth
                        // finishing - the graph is the deliverable, not the narration.
                        log.debug("Progress client disconnected: {}", e.toString());
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(summary));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Scan failed: {}", e.toString());
                try {
                    emitter.send(SseEmitter.event().name("failed")
                        .data(e.getMessage() == null ? e.toString() : e.getMessage()));
                } catch (Exception ignored) {
                    // Nothing left to report to.
                }
                emitter.complete();
            }
        });

        return emitter;
    }
}
