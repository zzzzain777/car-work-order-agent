# Policy Vector Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare a keyless Java pgvector connection and policy document mapping, then leave the policy retrieval decision logic to the project author.

**Architecture:** Reuse the existing policy loader and rule chunker. A small mapper turns each simulated rule chunk into a Spring AI `Document` with stable identity, provenance, and structured metadata. A separate opt-in probe uses a deterministic test embedding to verify Java ↔ PostgreSQL/pgvector behavior; it does not claim real semantic retrieval. The later retrieval method applies exact business filtering and evidence checks before returning an answer.

**Tech Stack:** Java 21, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, PostgreSQL 17 + pgvector 0.8.7.

**Spec:** [First-version design](../specs/2026-10-20-car-work-order-agent-design.md), sections 4, 7, and 8.

## Global Constraints

- Agent remains an independent Java project; do not modify or directly query `carSystem`.
- Policy data is simulated. Preserve policy ID, version, source reference, store, model, and effective interval in every mapped document.
- Default application startup and tests work without an Embedding API Key or PostgreSQL.
- No automatic policy ingestion on service startup; indexing must be an explicit operation.
- Use exact pgvector search first. Do not introduce HNSW or reranking before comparative evidence.
- Do not log API keys, Session Cookies, or customer data.

## Review Focus

- Two policy versions with the same rule number must produce different stable document IDs: test in Task 1.
- A chunk without a rule heading must retain its source file citation: test in Task 1.
- Repeated mapping must yield the same IDs and metadata: test in Task 1.
- Bad vector dimensions must be rejected by the database: test in Task 2.
- Default app boot must remain independent of the optional probe: run the full suite in Task 2.

---

### Task 1: Map policy chunks to traceable documents — Codex

**Files:**
- Create: `src/main/java/com/zz/agent/policy/PolicyVectorDocumentMapper.java`
- Test: `src/test/java/com/zz/agent/policy/PolicyVectorDocumentMapperTest.java`

**Interfaces:**
- Consumes: `PolicySource`, `PolicyRuleChunker.split(PolicySource)`.
- Produces: `List<Document> map(List<PolicySource> sources)`; each `Document` has a deterministic UUID ID and metadata keys `chunkId`, `policyId`, `version`, `sourceRef`, `storeId`, `modelCode`, `ruleType`, `status`, `simulated`, `effectiveFromEpoch`, `effectiveToEpoch`.

- [x] **Step 1: Write the failing test.** For an existing headed policy and a no-heading policy, assert one document per chunk, exact citation and metadata, and stable IDs across calls. Assert another version of the same policy yields another ID.
- [x] **Step 2: Run `PolicyVectorDocumentMapperTest`; expect failure because the mapper is absent.**
- [x] **Step 3: Implement `map(List<PolicySource>)` using `PolicyRuleChunker` and `UUID.nameUUIDFromBytes(chunkId.getBytes(UTF_8))`.** Keep the rule text as `Document` text; do not embed task or customer fields.
- [x] **Step 4: Rerun the focused test; expect pass.**
- [x] **Step 5: Commit the mapper and test.**

### Task 2: Verify keyless Java pgvector wiring — Codex

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/zz/agent/policy/PolicyPgVectorProbeTest.java`
- Create: `probes/stage-1/java-pgvector-smoke.sh`
- Create: `docs/verification/stage-1/java-pgvector-probe.md`

**Interfaces:**
- Consumes: `PolicyVectorDocumentMapper.map(List<PolicySource>)` and the Spring AI 1.1.2 `PgVectorStore` API.
- Produces: an opt-in integration test using a temporary PostgreSQL/pgvector container and a deterministic 1024-dimensional test `EmbeddingModel`. This checks insert, exact search, metadata filter, and 1024-dimensional storage without a paid API call.

- [x] **Step 1: Add a focused opt-in test that fails at compile time without the PGvector/JDBC dependencies.** Assert a known document can be inserted and found with matching metadata, excluded with wrong model metadata, and stored at 1024 dimensions.
- [x] **Step 2: Run the focused test; confirm the expected missing-dependency failure.**
- [x] **Step 3: Add only the Spring AI PGvector library, Spring JDBC, and PostgreSQL driver needed for manual test wiring.** Do not enable vector-store beans during ordinary app startup.
- [x] **Step 4: Start an isolated temporary pgvector container; run the probe with its test-only connection URL.** Save the result and limitations in the verification record, then remove the container.
- [x] **Step 5: Run the full suite with no Key and no DB; expect all default tests to pass.**
- [x] **Step 6: Commit the probe, dependencies, and verification record.**

### Handoff: Implement and evaluate retrieval policy — project author

**Files:**
- Create: `src/main/java/com/zz/agent/policy/PolicySemanticSearch.java`
- Test: `src/test/java/com/zz/agent/policy/PolicySemanticSearchTest.java`
- Extend: `src/test/java/com/zz/agent/policy/PolicyRetrievalEvaluationTest.java`

**Design ownership:** The author chooses the method signature, ranking, and evidence sufficiency rules. The implementation will consume eligible `PolicySource` records from `PolicyMetadataFilter`, mapped `Document` records, and a `VectorStore` search adapter; it will return status, ranked policy chunk IDs, and source references.

The author's next code task is to fix expected behavior from H01–H08 and the old five cases before coding. H01 must find the test-drive rule within top 2; H02 must not answer pricing from a test-drive rule; H03/H04/H08 exclude expired or wrong-model rules; H05 keeps both conflicting policies for human review. Then write focused failing tests for exact filtering, citation, and unsupported-question refusal, implement the search decision logic, and save a new versioned comparison without overwriting the old 6/8 evidence. Record model ID, dimension, corpus hash, case hash, rankings, citations, latency, and failure analysis.

Real semantic quality is unverified until a real Embedding model is configured and called. The keyless Task 2 probe establishes only Java/database compatibility and metadata behavior.
