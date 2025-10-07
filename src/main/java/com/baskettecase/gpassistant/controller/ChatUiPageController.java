package com.baskettecase.gpassistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ChatUiPageController {

    @GetMapping({"/", "/chat"})
    public String index() {
        return "forward:/index.html";
    }
}
