package e2e

class Example07WorkflowsTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  val infra = ServerInfra(
    appId = "e2e-workflows",
    jarModule = "workflows",
    mainClass = "workflows.workflowServer",
    postStart = _ => Thread.sleep(2_000),
  )
  override def munitFixtures = List(infra)

  private def startWorkflow(instanceId: String, input: String): Int =
    val (status, _) = DaprHttp.post(
      infra.daprHttpPort,
      s"/v1.0-beta1/workflows/dapr/OrderProcessingWorkflow/start?instanceID=$instanceId",
      input,
    )
    status

  private def pollUntilComplete(instanceId: String, timeoutMs: Long = 30_000): ujson.Value =
    val deadline = System.currentTimeMillis() + timeoutMs
    while System.currentTimeMillis() < deadline do
      val (_, body) = DaprHttp.get(infra.daprHttpPort, s"/v1.0-beta1/workflows/dapr/$instanceId")
      val json = ujson.read(body)
      val status = json.obj.get("runtimeStatus").map(_.str).getOrElse("")
      if status == "COMPLETED" || status == "FAILED" || status == "TERMINATED" then return json
      Thread.sleep(500)
    throw RuntimeException(s"Workflow $instanceId did not complete within ${timeoutMs}ms")

  private def workflowOutput(result: ujson.Value): ujson.Value =
    ujson.read(result("properties")("dapr.workflow.output").str)

  test("in-stock order succeeds") {
    val input = """{"orderId":"E2E-001","item":"widget","quantity":3,"budget":25.0}"""
    assertEquals(startWorkflow("e2e-wf-001", input), 202)
    val result = pollUntilComplete("e2e-wf-001")
    assertEquals(result("runtimeStatus").str, "COMPLETED")
    val output = workflowOutput(result)
    assertEquals(output("success").bool, true)
    assert(output("message").str.startsWith("shipped:"), clue(output))
  }

  test("out-of-stock order fails with 'out of stock'") {
    val input = """{"orderId":"E2E-002","item":"gadget","quantity":10,"budget":50.0}"""
    assertEquals(startWorkflow("e2e-wf-002", input), 202)
    val result = pollUntilComplete("e2e-wf-002")
    assertEquals(result("runtimeStatus").str, "COMPLETED")
    val output = workflowOutput(result)
    assertEquals(output("success").bool, false)
    assertEquals(output("message").str, "out of stock")
  }

  test("underfunded order fails with 'payment declined'") {
    val input = """{"orderId":"E2E-003","item":"gizmo","quantity":1,"budget":5.0}"""
    assertEquals(startWorkflow("e2e-wf-003", input), 202)
    val result = pollUntilComplete("e2e-wf-003")
    assertEquals(result("runtimeStatus").str, "COMPLETED")
    val output = workflowOutput(result)
    assertEquals(output("success").bool, false)
    assertEquals(output("message").str, "payment declined")
  }
