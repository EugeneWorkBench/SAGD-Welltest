//
//  Integrations layer
//
//  Platform primitives wrapped behind domain-facing interfaces: QR/barcode
//  scanning (AVFoundation / AVCaptureMetadataOutput) and device location
//  (CoreLocation / CLLocationManager) used for geotagged escalations.
//
//  The QR scanner (`QRScannerCoordinator` + `QRCodeSource` seam, with the
//  AVFoundation-backed `AVFoundationQRCodeSource`) is implemented in task 18.1
//  (see QRScanner.swift / AVFoundationQRCodeSource.swift). The location provider
//  and escalation capture follow in task 18.2.

import Foundation

/// Namespace marker for the Integrations layer.
enum IntegrationsLayer {}
