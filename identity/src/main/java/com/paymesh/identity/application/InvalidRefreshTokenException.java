package com.paymesh.identity.application;

/**
 * Raised whenever a presented refresh token cannot be spent: unknown, expired,
 * already rotated, or revoked by logout. One message for every case on purpose --
 * an attacker replaying a stolen token must not learn that the theft was detected,
 * and a client cannot act differently on the distinction anyway (all four mean
 * "log in again").
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Refresh token is invalid or has expired.");
    }
}
