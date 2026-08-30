package com.muster.roster;

import com.muster.common.PageResult;
import com.muster.roster.dto.PersonCreateRequest;
import com.muster.roster.dto.PersonResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/roster")
public class RosterController {

    private final RosterService rosterService;
    private final ExcelService excelService;

    public RosterController(RosterService rosterService, ExcelService excelService) {
        this.rosterService = rosterService;
        this.excelService = excelService;
    }

    @PostMapping("/import")
    public Map<String, Object> importPersons(@RequestParam("file") MultipartFile file) throws Exception {
        int imported = rosterService.importPersons(file.getInputStream());
        return Map.of("imported", imported);
    }

    @GetMapping
    public PageResult<PersonResponse> search(@RequestParam(defaultValue = "") String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return rosterService.search(keyword, page, size);
    }

    @PostMapping
    public PersonResponse add(@Valid @RequestBody PersonCreateRequest request) {
        return rosterService.add(request);
    }

    @PutMapping("/{id}")
    public PersonResponse update(@PathVariable Long id, @Valid @RequestBody PersonCreateRequest request) {
        return rosterService.update(id, request);
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        return Map.of("deleted", rosterService.clear());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        rosterService.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        byte[] bytes = excelService.writeTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("roster-template.xlsx", StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
