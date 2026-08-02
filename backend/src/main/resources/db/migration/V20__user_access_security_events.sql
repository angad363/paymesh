-- ============================================================================
--  V20__user_access_security_events.sql
--  Names the four things that can now happen to a user's access. ADR-024.
--
--  UserStatus.SUSPENDED and CLOSED became reachable in this change, and
--  revoking a user's roles at one merchant became possible. All four belong in
--  security_events, which is the table an investigator reads.
--
--  THEY GET THEIR OWN NAMES RATHER THAN REUSING 'LOGGED_OUT'. Suspending an
--  account revokes its sessions, so logging it as LOGGED_OUT would be true and
--  useless: the security log would show a suspension as somebody signing out,
--  and "who was barred and when" would be unanswerable from the one table that
--  exists to answer it.
-- ============================================================================

ALTER TABLE security_events
    DROP CONSTRAINT ck_security_events_type;

ALTER TABLE security_events
    ADD CONSTRAINT ck_security_events_type CHECK (
        event_type IN (
            'USER_REGISTERED',
            'LOGIN_SUCCEEDED',
            'LOGIN_FAILED',
            'TOKEN_REFRESHED',
            'REFRESH_TOKEN_REUSE_DETECTED',
            'LOGGED_OUT',
            -- Platform scope: the human is barred from PayMesh entirely.
            'USER_SUSPENDED',
            'USER_REACTIVATED',
            'USER_CLOSED',
            -- Merchant scope: they lost their roles at ONE merchant and keep
            -- their account, which is the departed-employee case (ADR-024).
            'MERCHANT_ACCESS_REVOKED'
        )
    );
