package dapr4s.wasm

/** The one fixed-name object a dapr4s WASI component must define (a reactor component has no `main`, so the dapr4s wasm
  * layer finds the app through this FQN at link time; a missing one is a link error).
  */
object ComponentRoot:
  def component: DaprComponent = serviceinvocation.GreetingServiceComponent
