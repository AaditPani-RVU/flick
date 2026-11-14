import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let server = Server()
    private let cards = CardPresenter()
    private var statusItem: NSStatusItem!

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            let image = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: "Flick")
            image?.isTemplate = true
            button.image = image
            if image == nil { button.title = "Flick" }
        }
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        server.onArrival = { [weak self] arrival in self?.handle(arrival) }
        server.onPairRequest = { [weak self] device, done in self?.askToPair(device, done) }
        server.start()
    }

    // MARK: Arrivals

    private func handle(_ arrival: Arrival) {
        switch arrival {
        case .file(let url, let power, _, _):
            cards.present(fileURL: url, power: power)
        case .text(let text, let power):
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            cards.present(text: text, power: power)
        }
    }

    private func askToPair(_ device: String, _ done: @escaping (Bool) -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Allow “\(device)” to flick files to this Mac?"
        alert.informativeText = "Files it sends are saved to \(Settings.destination.lastPathComponent) without asking again. You can forget paired phones from the Flick menu."
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Don’t Allow")
        done(alert.runModal() == .alertFirstButtonReturn)
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(disabled("Flick Receiver — \(server.machineName)"))
        if let failure = server.failure {
            menu.addItem(disabled("Not listening: \(failure)"))
        } else if server.port == 0 {
            menu.addItem(disabled("Starting…"))
        } else {
            let address = localIPv4() ?? "no Wi‑Fi address"
            menu.addItem(disabled("Listening at \(address):\(server.port)"))
        }
        menu.addItem(.separator())

        menu.addItem(disabled("Saving to \(Settings.destination.lastPathComponent)"))
        menu.addItem(item("Change Folder…", #selector(chooseFolder)))
        menu.addItem(item("Open Folder", #selector(openFolder)))
        menu.addItem(.separator())

        let phones = Set(Pairing.all.values).sorted()
        if phones.isEmpty {
            menu.addItem(disabled("No phones paired yet"))
        } else {
            for phone in phones { menu.addItem(disabled("Paired: \(phone)")) }
            menu.addItem(item("Forget All Phones", #selector(forgetPhones)))
        }
        menu.addItem(.separator())
        menu.addItem(item("Quit Flick Receiver", #selector(quit), key: "q"))
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func item(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        return item
    }

    @objc private func chooseFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = Settings.destination
        panel.prompt = "Save Here"
        if panel.runModal() == .OK, let url = panel.url {
            Settings.destination = url
        }
    }

    @objc private func openFolder() {
        NSWorkspace.shared.open(Settings.destination)
    }

    @objc private func forgetPhones() {
        Pairing.forgetAll()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    /// First non-loopback IPv4 address, for typing into the phone when discovery is blocked.
    private func localIPv4() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var fallback: String?
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  (entry.ifa_flags & UInt32(IFF_LOOPBACK)) == 0,
                  (entry.ifa_flags & UInt32(IFF_UP)) != 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if String(cString: entry.ifa_name) == "en0" { return ip }
            if fallback == nil { fallback = ip }
        }
        return fallback
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
