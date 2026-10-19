package com.example.videoagent.dto;

public record LoginResponse(String token, String tokenType, long expiresIn, long userId) {
}
