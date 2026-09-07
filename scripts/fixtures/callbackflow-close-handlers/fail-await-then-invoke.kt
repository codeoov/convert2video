package fixture

fun failAwaitThenInvoke() {
    callbackFlow {
        awaitClose { }
        if (true) {
            invokeOnClose { }
        }
    }
}
