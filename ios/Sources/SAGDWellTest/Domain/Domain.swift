//
//  Domain layer
//
//  Pure, platform-agnostic business logic: SubcoolEstimator, production and
//  injection log validators, well-lookup use case, and escalation assembly.
//  Deterministic functions over their inputs with no I/O. This layer is the
//  primary target for property-based tests.
//
//  Dependency rule: the Domain layer depends on nothing in the UI, persistence
//  engine, or networking. Repository and use-case protocols defined here are
//  implemented by lower layers.
//
//  Types are added in task 1.2 and the domain-logic tasks (8, 9, 10).

import Foundation

/// Namespace marker for the Domain layer. Concrete types are introduced by
/// later tasks; this keeps the layer group present and documented.
enum DomainLayer {}
