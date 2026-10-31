package com.moveinsync.roster.reconciliation;

import com.moveinsync.roster.security.JwtClaims;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<RunReport> start(@RequestPart("file") MultipartFile file, @RequestParam("generatedAt") Instant generatedAt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconciliation.start(file, generatedAt));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunReport find(@PathVariable long id) {
        return reconciliation.find(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunReport approve(@PathVariable long id, @AuthenticationPrincipal JwtClaims operator) {
        return reconciliation.approve(id, operator.username());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunReport reject(@PathVariable long id, @AuthenticationPrincipal JwtClaims operator) {
        return reconciliation.reject(id, operator.username());
    }
}
