package fixture

fun passNestedAdjacent() {
    callbackFlow {
        if (true) {
            awaitClose { }
        }
    }
    callbackFlow {
        try {
            invokeOnClose { }
        } finally {
            send(Unit)
        }
    }
}
