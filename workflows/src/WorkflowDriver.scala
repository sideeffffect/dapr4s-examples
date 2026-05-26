package workflows

import dapr4s.*
import scala.concurrent.duration.*
import OrderDomain.given

// ── Driver ────────────────────────────────────────────────────────────────────
// WorkflowCapability is scoped to the DaprRuntime.run block.  Each
// WorkflowCapability.start call returns a WorkflowInstanceId — a plain value
// with no captures — which can be stored and passed around freely.  The
// capability itself, however, cannot escape the block.

@main def workflowDriver(): Unit =
  println("=== 07 workflows: OrderProcessingWorkflow driver ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):
    DaprCapability.workflow:

      val workflowName = WorkflowName("OrderProcessingWorkflow")

      def runOrder(order: OrderRequest): Unit =
        val id = WorkflowCapability.start(workflowName, order)
        println(s"\nStarted workflow ${id.value} for order ${order.orderId}")

        val snapshot = WorkflowCapability.waitForCompletion(id, 30.seconds)
        snapshot match
          case None =>
            println(s"  Timed out waiting for ${order.orderId}")
          case Some(snap) =>
            val resultText = snap.serializedOutput
              .flatMap(_.decode[OrderResult].toOption)
              .map(r => s"success=${r.success}, message='${r.message}'")
              .getOrElse("(no output)")
            println(s"  ${snap.status}: $resultText")

      // ── Happy path ─────────────────────────────────────────────────────────
      runOrder(OrderRequest("ORD-001", item = "widget",  quantity = 3,  budget = 25.0))

      // ── Out of stock ───────────────────────────────────────────────────────
      runOrder(OrderRequest("ORD-002", item = "gadget",  quantity = 10, budget = 50.0))

      // ── Payment declined ───────────────────────────────────────────────────
      runOrder(OrderRequest("ORD-003", item = "gizmo",   quantity = 1,  budget = 5.0))

  println("\n[workflow-driver] done.")
