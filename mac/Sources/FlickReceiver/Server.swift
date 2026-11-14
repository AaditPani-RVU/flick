import Foundation
import Network

/// What the phone sent, handed to the UI on the main thread.
enum Arrival {
    case file(url: URL, power: Double, index: Int, count: Int)
    case text(String, power: Double)
}

/// Tiny HTTP server advertised over Bonjour as `_flickdrop._tcp`.
///
///   GET  /hello          -> {"name": ..., "paired": bool}
///   POST /pair           -> asks the user, returns {"token": ...}
///   POST /send           -> raw file body, saved to the destination folder
///   POST /text           -> UTF-8 text body, copied to the clipboard
final class Server {
    static let serviceType = "_flickdrop._tcp"
    static let preferredPort: UInt16 = 47531

    let queue = DispatchQueue(label: "flick.server")
    private var listener: NWListener?
    private var connections = Set<ObjectIdentifier>()
    private var retained: [ObjectIdentifier: HTTPConnection] = [:]

    var onArrival: ((Arrival) -> Void)?
    /// Called on the main thread; must call the completion with the user's choice.
    var onPairRequest: ((String, @escaping (Bool) -> Void) -> Void)?
    var onStateChange: (() -> Void)?

    private(set) var port: UInt16 = 0
    private(set) var failure: String?

    var machineName: String { Host.current().localizedName ?? "Mac" }

    func start() {
        let made = (try? makeListener(port: Server.preferredPort)) ?? (try? makeListener(port: 0))
        guard let listener = made else {
            failure = "Could not open a network port"
            notifyState()
            return
        }
        self.listener = listener
        listener.service = NWListener.Service(name: machineName, type: Server.serviceType)
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = listener.port?.rawValue ?? 0
                self.failure = nil
            case .failed(let error):
                self.failure = error.localizedDescription
            default:
                break
            }
            self.notifyState()
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            let http = HTTPConnection(connection: connection, server: self)
            self.retained[ObjectIdentifier(http)] = http
            http.start()
        }
        listener.start(queue: queue)
    }

    private func makeListener(port: UInt16) throws -> NWListener {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if port == 0 { return try NWListener(using: params) }
        return try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
    }

    func release(_ http: HTTPConnection) {
        retained[ObjectIdentifier(http)] = nil
    }

    private func notifyState() {
        DispatchQueue.main.async { self.onStateChange?() }
    }
}

final class HTTPConnection {
    private let connection: NWConnection
    private unowned let server: Server

    private var buffer = Data()
    private var method = ""
    private var path = ""
    private var headers: [String: String] = [:]

    private var remaining: Int64 = 0
    private var fileHandle: FileHandle?
    private var partURL: URL?
    private var smallBody = Data()
    private var finished = false

    init(connection: NWConnection, server: Server) {
        self.connection = connection
        self.server = server
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.cleanup()
            default: break
            }
        }
        connection.start(queue: server.queue)
        readHeader()
    }

    // MARK: Reading

    private func readHeader() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
            if let data { self.buffer.append(data) }
            if let range = self.buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: self.buffer[self.buffer.startIndex..<range.lowerBound], as: UTF8.self)
                let rest = Data(self.buffer[range.upperBound...])
                self.buffer = Data()
                self.parse(head)
                self.route(initialBody: rest)
            } else if error != nil || isComplete || self.buffer.count > 65536 {
                self.connection.cancel()
            } else {
                self.readHeader()
            }
        }
    }

    private func parse(_ head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        if parts.count >= 2 {
            method = String(parts[0]).uppercased()
            path = String(parts[1])
        }
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
    }

    private func route(initialBody: Data) {
        let length = Int64(headers["content-length"] ?? "0") ?? 0
        let token = headers["x-flick-token"] ?? ""

        switch (method, path) {
        case ("GET", "/hello"):
            respond(200, ["name": server.machineName, "paired": Pairing.isValid(token)])

        case ("POST", "/pair"):
            let device = decoded(headers["x-flick-device"]) ?? "A phone"
            DispatchQueue.main.async {
                guard let ask = self.server.onPairRequest else {
                    self.server.queue.async { self.respond(403, ["error": "unavailable"]) }
                    return
                }
                ask(device) { allowed in
                    self.server.queue.async {
                        if allowed {
                            self.respond(200, ["token": Pairing.add(device: device), "name": self.server.machineName])
                        } else {
                            self.respond(403, ["error": "denied"])
                        }
                    }
                }
            }

        case ("POST", "/send"):
            guard Pairing.isValid(token) else { return respond(401, ["error": "not paired"]) }
            guard length >= 0 else { return respond(400, ["error": "bad length"]) }
            let folder = Settings.destination
            let part = folder.appendingPathComponent(".flick-\(UUID().uuidString).part")
            guard FileManager.default.createFile(atPath: part.path, contents: nil),
                  let handle = try? FileHandle(forWritingTo: part) else {
                return respond(500, ["error": "cannot write to \(folder.lastPathComponent)"])
            }
            partURL = part
            fileHandle = handle
            remaining = length
            consumeFile(initialBody)

        case ("POST", "/text"):
            guard Pairing.isValid(token) else { return respond(401, ["error": "not paired"]) }
            guard length >= 0, length <= 1_000_000 else { return respond(413, ["error": "too large"]) }
            remaining = length
            consumeText(initialBody)

        default:
            respond(404, ["error": "not found"])
        }
    }

    private func consumeFile(_ chunk: Data) {
        if !chunk.isEmpty {
            let slice = chunk.prefix(Int(min(Int64(chunk.count), remaining)))
            do {
                try fileHandle?.write(contentsOf: slice)
            } catch {
                return respond(500, ["error": "disk write failed"])
            }
            remaining -= Int64(slice.count)
        }
        if remaining <= 0 { return finishFile() }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { data, _, isComplete, error in
            if let data, !data.isEmpty {
                self.consumeFile(data)
            } else if error != nil || isComplete {
                self.connection.cancel()
            } else {
                self.consumeFile(Data())
            }
        }
    }

    private func finishFile() {
        try? fileHandle?.close()
        fileHandle = nil
        guard let part = partURL else { return respond(500, ["error": "lost file"]) }
        let name = sanitized(decoded(headers["x-flick-name"]) ?? "Flicked file")
        let target = uniqueURL(in: part.deletingLastPathComponent(), name: name)
        do {
            try FileManager.default.moveItem(at: part, to: target)
        } catch {
            return respond(500, ["error": "could not save"])
        }
        partURL = nil
        let power = Double(headers["x-flick-power"] ?? "") ?? 0.5
        let index = Int(headers["x-flick-index"] ?? "") ?? 0
        let count = Int(headers["x-flick-count"] ?? "") ?? 1
        respond(200, ["saved": target.lastPathComponent])
        DispatchQueue.main.async {
            self.server.onArrival?(.file(url: target, power: power, index: index, count: count))
        }
    }

    private func consumeText(_ chunk: Data) {
        let slice = chunk.prefix(Int(min(Int64(chunk.count), remaining)))
        smallBody.append(slice)
        remaining -= Int64(slice.count)
        if remaining <= 0 {
            let text = String(decoding: smallBody, as: UTF8.self)
            let power = Double(headers["x-flick-power"] ?? "") ?? 0.5
            respond(200, ["ok": true])
            DispatchQueue.main.async { self.server.onArrival?(.text(text, power: power)) }
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
            if let data, !data.isEmpty {
                self.consumeText(data)
            } else if error != nil || isComplete {
                self.connection.cancel()
            } else {
                self.consumeText(Data())
            }
        }
    }

    // MARK: Responding

    private func respond(_ status: Int, _ json: [String: Any]) {
        guard !finished else { return }
        finished = true
        let body = (try? JSONSerialization.data(withJSONObject: json)) ?? Data("{}".utf8)
        let reason = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden",
                      404: "Not Found", 413: "Payload Too Large", 500: "Server Error"][status] ?? "OK"
        var out = Data("HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n".utf8)
        out.append(body)
        connection.send(content: out, completion: .contentProcessed { _ in
            self.connection.cancel()
        })
    }

    private func cleanup() {
        try? fileHandle?.close()
        fileHandle = nil
        if let part = partURL {
            try? FileManager.default.removeItem(at: part)
            partURL = nil
        }
        server.release(self)
    }

    // MARK: Names

    private func decoded(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value.removingPercentEncoding ?? value
    }

    private func sanitized(_ name: String) -> String {
        var clean = (name as NSString).lastPathComponent
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        while clean.hasPrefix(".") { clean.removeFirst() }
        return clean.isEmpty ? "Flicked file" : clean
    }

    private func uniqueURL(in folder: URL, name: String) -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = folder.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let next = ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)"
            candidate = folder.appendingPathComponent(next)
            n += 1
        }
        return candidate
    }
}

/// Phones that the user has allowed. Token -> device name.
enum Pairing {
    private static let key = "pairedTokens"

    static var all: [String: String] {
        UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    static func isValid(_ token: String) -> Bool {
        !token.isEmpty && all[token] != nil
    }

    static func add(device: String) -> String {
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let token = bytes.map { String(format: "%02x", $0) }.joined()
        var tokens = all
        tokens[token] = device
        UserDefaults.standard.set(tokens, forKey: key)
        return token
    }

    static func forgetAll() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}

enum Settings {
    private static let key = "destinationPath"

    static var destination: URL {
        get {
            if let path = UserDefaults.standard.string(forKey: key),
               FileManager.default.fileExists(atPath: path) {
                return URL(fileURLWithPath: path)
            }
            return FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSHomeDirectory())
        }
        set { UserDefaults.standard.set(newValue.path, forKey: key) }
    }
}
