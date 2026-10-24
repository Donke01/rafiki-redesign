import SwiftUI

/// Who an e-mail goes to when Office › Send an e-mail is opened from somewhere else (a contact sheet, Donors, a project).
struct MailPreset: Equatable { var aud: String; var sub = "all"; var project = ""; var picks: [String] = [] }
enum MailNav { static var preset: MailPreset? }
