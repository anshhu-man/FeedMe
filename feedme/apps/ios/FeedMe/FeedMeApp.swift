import FeedMeShared
import SwiftUI
import UIKit

@main
struct FeedMeApp: App {
    var body: some Scene {
        WindowGroup {
            SharedAppView()
                .ignoresSafeArea()
        }
    }
}

/// Swift owns the native lifecycle; Compose owns the shared application UI.
private struct SharedAppView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
