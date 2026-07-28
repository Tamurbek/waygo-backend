package com.waygo.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@Tag(name = "Payment Success Controller", description = "Public redirect page for completed acquiring payments")
public class PaymentSuccessController {

    @GetMapping(value = "/payment-success", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    @Operation(summary = "Render payment success HTML page and auto-redirect back to mobile app")
    public String paymentSuccess() {
        return """
            <!DOCTYPE html>
            <html lang="uz">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>To'lov muvaffaqiyatli o'tdi | WayGo</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
                    body { background-color: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 20px; }
                    .card { background-color: #1e293b; border: 1px solid #334155; border-radius: 24px; padding: 40px 24px; max-width: 400px; width: 100%; text-align: center; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5); }
                    .icon-box { width: 80px; height: 80px; background-color: rgba(34, 197, 94, 0.15); border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 20px; border: 2px solid #22c55e; }
                    .icon-box svg { width: 44px; height: 44px; stroke: #22c55e; stroke-width: 3; fill: none; stroke-linecap: round; stroke-linejoin: round; }
                    h1 { font-size: 22px; font-weight: 800; color: #ffffff; margin-bottom: 10px; }
                    p { font-size: 14px; color: #94a3b8; line-height: 1.5; margin-bottom: 28px; }
                    .btn { display: block; width: 100%; background-color: #2563eb; color: #ffffff; text-decoration: none; font-weight: 700; font-size: 16px; padding: 14px 20px; border-radius: 14px; border: none; transition: background-color 0.2s; }
                    .btn:hover { background-color: #1d4ed8; }
                    .subtext { font-size: 12px; color: #64748b; margin-top: 16px; }
                </style>
                <script>
                    setTimeout(function() {
                        window.location.href = "waygodriver://payment-success";
                    }, 500);
                </script>
            </head>
            <body>
                <div class="card">
                    <div class="icon-box">
                        <svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"></polyline></svg>
                    </div>
                    <h1>To'lov muvaffaqiyatli o'tdi!</h1>
                    <p>Balansingiz muvaffaqiyatli to'ldirildi. WayGo Driver ilovasiga qaytib ishlashda davom etishingiz mumkin.</p>
                    <a href="waygodriver://payment-success" class="btn">WayGo Driver Ilovasiga Qaytish</a>
                    <div class="subtext">Oyna yopilmasa, tepadagi "X" tugmasini bosing</div>
                </div>
            </body>
            </html>
            """;
    }
}
