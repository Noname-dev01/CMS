package com.cms.member.controller;

import com.cms.member.domain.Member;
import com.cms.member.service.MemberService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("member", new Member());
        return "/admin/register";
    }

    @PostMapping("/signup")
    public String signupSubmit(@ModelAttribute("member") Member member, Model model) {
        if (memberService.isUserIdTaken(member.getUserId())) {
            model.addAttribute("error", "사용중인 아이디입니다.");
            return "/admin/register";
        }
        memberService.registerMember(member);
        return "redirect:/admin/login";
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "/admin/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String userId,
                                      @RequestParam String email,
                                      RedirectAttributes redirectAttributes) {
        try {
            memberService.sendPasswordResetEmail(userId, email);
            redirectAttributes.addFlashAttribute("message", "비밀번호 재설정 링크가 이메일로 전송되었습니다.");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/member/forgot-password";
        }
        return "redirect:/admin/login";
    }

    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "/admin/reset-password";
    }

    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam String token,
                                     @RequestParam String password,
                                     RedirectAttributes redirectAttributes) {
        if (memberService.resetPassword(token, password)) {
            redirectAttributes.addFlashAttribute("message", "비밀번호가 성공적으로 변경되었습니다.");
            return "redirect:/admin/login";
        } else {
            redirectAttributes.addFlashAttribute("error", "유효하지 않은 토큰입니다.");
            return "redirect:/member/forgot-password";
        }
    }
}
