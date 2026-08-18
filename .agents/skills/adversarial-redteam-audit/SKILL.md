---
name: adversarial-redteam-audit
description: >-
  Adversarial Red-Team Sceptic & Saboteur code audit skill.
  Forces active falsification, stress-testing edge cases, concurrency races, network drops, memory leaks, and Android lifecycle violations.
---

# ADVERSARIAL RED-TEAM CODE AUDIT PROTOCOL

## Core Philosophy
Assume every happy path is an illusion. The auditor's role is to break the implementation, discover silent data corruption, and challenge all implicit assumptions.

## 4 Mandatory Red-Team Lenses

### 1. The Network Adversary
- What happens if the server returns 403, 404, 410, 429, or 503?
- What happens if the connection is terminated mid-stream (ECONNRESET)?
- What happens if the CDN token expires after 15 minutes of pause?
- Does OkHttp or native CLI deadlock on unread socket buffers?

### 2. The Concurrency & Race Adversary
- Are multiple threads/coroutines writing to the same data structures without synchronization?
- Can rapid user actions (Pause -> Resume -> Cancel in 500ms) corrupt the state machine?
- Are progress calculations or time deltas vulnerable to divide-by-zero or negative deltas?

### 3. The Filesystem & Memory Adversary
- Does Android Scoped Storage FUSE daemon intercept zero-allocated files prematurely?
- Are partial downloads safely isolated with temporary extensions (.part) before final commit?
- Are file channels, random access handles, and streams guaranteed to close on coroutine cancellation?

### 4. The Android Lifecycle Adversary
- Can background downloads survive system Doze mode and memory trim events?
- Does ForegroundService handle start-not-allowed exceptions gracefully?
- Are UI touch targets at least 48dp with zero frame-dropping during rapid LazyColumn scrolling?
