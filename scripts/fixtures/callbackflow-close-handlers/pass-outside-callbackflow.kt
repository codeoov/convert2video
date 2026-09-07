package fixture

fun passOutsideCallbackFlow() {
    invokeOnClose { }
    awaitClose { }
    callbackFlow {
        send(Unit)
    }
}
