package fixture

fun failInvokeThenAwait() {
    callbackFlow {
        invokeOnClose { }
        run {
            awaitClose { }
        }
    }
}
