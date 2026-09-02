package com.localmind.chat;
import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/chat")
public class ChatController {private final ChatService service;public ChatController(ChatService s){service=s;} @PostMapping public ChatService.ChatResponse ask(@Valid @RequestBody Request r){return service.ask(r.message());} public record Request(@NotBlank String message){} }
