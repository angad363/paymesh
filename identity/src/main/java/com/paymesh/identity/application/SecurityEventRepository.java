package com.paymesh.identity.application;

import com.paymesh.identity.domain.SecurityEvent;

/** Write-only: nothing in the request path reads the audit trail. */
public interface SecurityEventRepository {

    void save(SecurityEvent securityEvent);
}
