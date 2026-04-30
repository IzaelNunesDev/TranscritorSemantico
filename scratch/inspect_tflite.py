import re
import sys

def find_signatures(file_path):
    with open(file_path, 'rb') as f:
        content = f.read()
        
    patterns = [
        b'serving_transcribe',
        b'serving_translate',
        b'serving_default',
        b'input_features',
        b'sequences',
        b'output',
        b'input'
    ]
    
    found = {}
    for p in patterns:
        matches = list(re.finditer(p, content))
        if matches:
            found[p.decode()] = len(matches)
            
    print(f"Found patterns: {found}")
    
    # Try to find all strings that look like signatures/tensors
    all_strings = re.findall(b'[a-zA-Z0-9_]{5,}', content)
    unique_strings = sorted(list(set(s.decode(errors='ignore') for s in all_strings)))
    
    relevant = [s for s in unique_strings if any(p in s.lower() for p in ['transcribe', 'translate', 'serving', 'input', 'output', 'sequence'])]
    print(f"Relevant strings: {relevant}")

if __name__ == "__main__":
    find_signatures(sys.argv[1])
