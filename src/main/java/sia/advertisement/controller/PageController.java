package sia.advertisement.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    
    @GetMapping("/")
    public String index() {
        return "try";
    }
    
    @GetMapping("/editor")
    public String editor() {
        return "try";
    }
    
    @GetMapping("/project/{id}")
    public String projectEditor() {
        return "try";
    }

    @GetMapping("/preview")
    public String preview() {
        return "preview";
    }

    @GetMapping("/showcase")
    public String showcase() {
        return "showcase";
    }

    @GetMapping("/vault")
    public String vault() {
        return "vault";
    }
}
