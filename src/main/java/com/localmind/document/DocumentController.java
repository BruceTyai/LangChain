package com.localmind.document;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/documents")
public class DocumentController {
 private final DocumentService service; public DocumentController(DocumentService s){service=s;}
 @GetMapping public List<KnowledgeDocument> list(){return service.list();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public KnowledgeDocument upload(@RequestParam("file") MultipartFile file){return service.ingest(file);}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable long id){service.delete(id);}
}
