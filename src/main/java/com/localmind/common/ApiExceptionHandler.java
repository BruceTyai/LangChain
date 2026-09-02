package com.localmind.common;
import java.util.Map; import org.springframework.http.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) Map<String,String> bad(Exception e){return Map.of("message",e instanceof MethodArgumentNotValidException?"请求参数不完整":e.getMessage());}
 @ExceptionHandler(Exception.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) Map<String,String> error(Exception e){return Map.of("message",e.getMessage()==null?"服务暂时不可用":e.getMessage());}
}
