//
//  Network layer
//
//  URLSession-based REST client for the Cloud_Backend push endpoints
//  (/v1/well-tests, /v1/wellhead-logs, /v1/subcool-readings, /v1/escalations)
//  and pull endpoints (well, tests desc, open tasks, incremental wells). Parses
//  the acknowledgment contract and classifies retryable vs non-retryable
//  responses.
//
//  The API client is implemented in task 22.1 as `CloudBackendClient` over the
//  `HTTPTransport` seam (see CloudBackendClient.swift). Wiring it into the sync
//  engine's dependency graph is task 22.2.

import Foundation

/// Namespace marker for the Network layer.
enum NetworkLayer {}
