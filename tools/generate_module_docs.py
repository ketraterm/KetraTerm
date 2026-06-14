import os

modules = [
    "jvterm-protocol",
    "jvterm-parser",
    "jvterm-core",
    "jvterm-host",
    "jvterm-input",
    "jvterm-render-api",
    "jvterm-render-cache",
    "jvterm-transport-api",
    "jvterm-session",
    "jvterm-ui-swing",
    "jvterm-testkit",
    "jvterm-pty",
    "jvterm-workspace"
]

for module in modules:
    readme_path = os.path.join(module, "README.md")
    module_md_path = os.path.join(module, "Module.md")
    if os.path.exists(readme_path):
        with open(readme_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        
        header = f"# Module {module}\n\n"
        
        new_lines = []
        first_header_converted = False
        for line in lines:
            if line.strip().startswith("# ") and not first_header_converted:
                # Convert the main title to a second-level header to comply with Dokka's single "#" constraint
                stripped = line.lstrip()
                new_lines.append("## " + stripped[2:])
                first_header_converted = True
            else:
                new_lines.append(line)
        
        with open(module_md_path, "w", encoding="utf-8") as f:
            f.write(header)
            f.writelines(new_lines)
        print(f"Generated {module_md_path}")
    else:
        print(f"Skipping {module}: README.md not found")
