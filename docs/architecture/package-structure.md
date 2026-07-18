# PayMesh Package Structure

## Current approach

PayMesh begins as a modular monolith.

The application is deployed as one Spring Boot process, but business
capabilities will be separated into explicit Java package modules.

## Root package

The Spring Boot application class remains in:
com.paymesh
